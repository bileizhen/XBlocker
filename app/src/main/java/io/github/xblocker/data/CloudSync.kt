package io.github.xblocker.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.xblocker.core.RuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object CloudSync {
    const val SOURCE = "https://github.com/amahteru/x-comment-blocker"
    private const val API = "https://api.github.com/repos/amahteru/x-comment-blocker/contents/keywords.txt"
    private const val RAW = "https://raw.githubusercontent.com/amahteru/x-comment-blocker/main/keywords.txt"
    private val lock = Any()
    fun sync(repo: Repository): kotlin.Result<Unit> = synchronized(lock) {
        runCatching {
            if (!repo.settings().cloudEnabled) return@runCatching
            var lastError: Exception? = null
            for (url in listOf(API, RAW)) {
                var connection: HttpURLConnection? = null
                try {
                    connection = URI(url).toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", "XBlocker/${io.github.xblocker.BuildConfig.VERSION_NAME}")
                    connection.setRequestProperty("Accept", "application/vnd.github.raw+json")
                    if (url == API && repo.etag().isNotBlank()) connection.setRequestProperty("If-None-Match", repo.etag())
                    val status = connection.responseCode
                    if (status == 304 && url == API) { repo.synced(null, repo.etag()); return@runCatching }
                    check(status == 200) { "HTTP $status" }
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 512 * 1024) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 512 * 1024 + 1 - output.size()))
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    check(bytes.size <= 512 * 1024) { "词库超过大小限制" }
                    val text = bytes.toString(Charsets.UTF_8)
                    check(!text.trimStart().startsWith('<') && !text.trimStart().startsWith('{') && RuleParser.parse(text).size in 1..15_000) { "词库格式无效" }
                    repo.synced(text, if (url == API) connection.getHeaderField("ETag").orEmpty() else "")
                    return@runCatching
                } catch (e: Exception) { lastError = e } finally { connection?.disconnect() }
            }
            throw lastError ?: IllegalStateException("无法获取词库")
        }.onFailure { repo.syncFailed("同步失败，保留本地词库：${it.message}") }
    }
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("cloud-rules", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = Repository(applicationContext)
        if (!repo.settings().cloudEnabled) return@withContext Result.success()
        if (CloudSync.sync(repo).isSuccess) Result.success() else Result.retry()
    }
}
