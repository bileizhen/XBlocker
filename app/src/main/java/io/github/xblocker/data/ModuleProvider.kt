package io.github.xblocker.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/** Read-only configuration bridge. Only X's UID and our own UID may access it. */
class ModuleProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        if (uid != Process.myUid() && "com.twitter.android" !in packages) throw SecurityException("Caller not in module scope")
        val repository = Repository(ctx)
        return when (method) {
            "snapshot" -> Bundle().apply { putString("json", repository.snapshot()) }
            "report" -> {
                val payload = extras?.getString("json").orEmpty()
                require(payload.length <= 150_000)
                val report = JSONObject(payload)
                repository.report(report)
                // X normally owns the live notification. Keep a module-owned fallback for
                // older clients that do not mark the report as X-owned.
                val identity = Binder.clearCallingIdentity()
                try {
                    if (!report.optBoolean("xOwned", false)) {
                        runCatching {
                            val diagnostics = repository.diagnostics()
                            if (repository.fluidCloud()) io.github.xblocker.fluid.FluidStatus.ensureChannels(ctx)
                                .let { io.github.xblocker.fluid.FluidStatus.onDiagnostics(ctx, diagnostics) }
                            else ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(io.github.xblocker.fluid.FluidStatus.CAPSULE_ID)
                            if (repository.focusNotification()) io.github.xblocker.fluid.FocusStatus.ensureChannels(ctx)
                                .let { io.github.xblocker.fluid.FocusStatus.onDiagnostics(ctx, diagnostics) }
                            else ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(io.github.xblocker.fluid.FocusStatus.NOTIFICATION_ID)
                        }.onFailure { android.util.Log.w("XBlocker.Fluid", "Report notification failed", it) }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unknown bridge operation")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
