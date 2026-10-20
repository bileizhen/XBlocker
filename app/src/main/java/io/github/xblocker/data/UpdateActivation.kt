package io.github.xblocker.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.xblocker.BuildConfig
import io.github.xblocker.hook.DexKitLocator
import io.github.xblocker.hook.MediaMenuLocator
import io.github.xblocker.hook.SspAdLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

/** Post-update discovery only. Host restart attempts were removed after device validation. */
object UpdateActivation {
    fun afterPackageReplaced(context: Context): Operation? {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val store = UpdateActivationStore(context)
        val prefix = "${BuildConfig.VERSION_CODE}:${installed.lastUpdateTime}:"
        val current = store.read()
        if (current?.token?.startsWith(prefix) == true) return if (current.running) enqueue(context, current.token) else null
        val token = prefix + UUID.randomUUID()
        return if (store.begin(token)) enqueue(context, token) else null
    }
    fun retry(context: Context) {
        val store = UpdateActivationStore(context)
        val state = store.read() ?: return
        if (state.running) return
        val token = state.token.substringBeforeLast(':') + ":" + UUID.randomUUID()
        if (store.begin(token)) enqueue(context, token)
    }
    fun recoverQueued(context: Context) {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val state = UpdateActivationStore(context).read()
        val prefix = "${BuildConfig.VERSION_CODE}:${installed.lastUpdateTime}:"
        if (state == null || !state.token.startsWith(prefix)) {
            if (installed.firstInstallTime != installed.lastUpdateTime) afterPackageReplaced(context)
        } else if (state.running) enqueue(context, state.token)
    }
    private fun enqueue(context: Context, token: String): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        "xblocker-update-discovery:$token", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<UpdateActivationWorker>().setInputData(workDataOf("token" to token)).build())
}

class UpdateActivationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString("token") ?: return@withContext Result.success()
        val store = UpdateActivationStore(applicationContext)
        val state = store.read()?.takeIf { it.token == token && it.running } ?: return@withContext Result.success()
        store.update(token) { it.copy(phase = ActivationPhase.SCANNING) }
        val scan = DexKitLocator.scan(applicationContext)
        val media = MediaMenuLocator.resolve(applicationContext).report
        val ssp = SspAdLocator.resolve(applicationContext).report
        val combined = scan.toJson().put("media", media).put("ssp", ssp)
            .put("total", scan.total + media.optInt("total") + ssp.optInt("total"))
            .put("success", scan.success + media.optInt("success") + ssp.optInt("success"))
            .put("mode", if (scan.mode == "cache" && media.optString("mode") == "cache" && ssp.optString("mode") == "cache") "cache" else "dexkit")
        currentCoroutineContext().ensureActive()
        if (!isStopped) store.update(state.token) { it.copy(scan = combined, phase = if (combined.optInt("success") == combined.optInt("total")) ActivationPhase.READY else ActivationPhase.FAILED) }
        Result.success()
    }
}
