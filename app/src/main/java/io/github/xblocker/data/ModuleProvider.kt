package io.github.xblocker.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/** Configuration, diagnostics and user-initiated media downloads. Restricted to X's UID and our own UID. */
class ModuleProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        if (uid != Process.myUid() && "com.twitter.android" !in packages) throw SecurityException("Caller not in module scope")
        val repository = Repository(ctx)
        fun registerUser(request: Bundle, folder: String) {
            if (folder.isEmpty()) return
            MediaUserStore(ctx).save(folder, request.getString("userId").orEmpty(),
                request.getString("userHandle").orEmpty(), request.getString("userName"),
                request.getString("userAvatar"))
        }
        return when (method) {
            "downloadMedia" -> {
                val identity = Binder.clearCallingIdentity()
                try {
                    val request = requireNotNull(extras)
                    val folder = request.getString("folder")?.take(64)
                    if (!folder.isNullOrEmpty() && !request.getString("userHandle").isNullOrEmpty()) {
                        registerUser(request, folder)
                    }
                    val history = io.github.xblocker.download.MediaDownloadHistory(ctx)
                    val batchId = folder?.takeIf { it.isNotEmpty() }?.let {
                        history.start(it, request.getString("userName"), request.getString("userHandle"),
                            request.getString("userAvatar"))
                    }
                    val result = io.github.xblocker.download.MediaDownload.enqueue(ctx,
                        request.getString("url").orEmpty(), request.getString("tweetId").orEmpty(),
                        request.getString("mediaId").orEmpty(), request.getString("type").orEmpty(),
                        folder, batchId)
                    if (batchId != null) {
                        // A deduped "already queued" item is finished by the older batch record.
                        if (result.queued && !result.alreadyQueued) history.setTotal(batchId, 1) else history.remove(batchId)
                    }
                    Bundle().apply { putBoolean("queued", result.queued); putString("requestId", result.requestId); putString("message", result.message) }
                } finally { Binder.restoreCallingIdentity(identity) }
            }
            "registerMediaUser" -> {
                // Metadata for the per-user folder shown in the Media tab. Every field is re-validated by the store.
                val request = requireNotNull(extras)
                registerUser(request, io.github.xblocker.download.MediaDownloadPolicy.folder(request.getString("folder")))
                Bundle.EMPTY
            }
            "downloadMediaBatch" -> {
                val request = requireNotNull(extras)
                val folder = io.github.xblocker.download.MediaDownloadPolicy.folder(request.getString("folder"))
                val items = request.getString("items").orEmpty()
                require(items.length <= 1_000_000) { "Media batch too large" }
                registerUser(request, folder)
                val history = io.github.xblocker.download.MediaDownloadHistory(ctx)
                val batchId = history.start(folder, request.getString("userName"),
                    request.getString("userHandle"), request.getString("userAvatar"))
                // The provider runs under X's identity; WorkManager and the MediaStore dedupe scan
                // must execute with this app's identity.
                val identity = Binder.clearCallingIdentity()
                val result = try {
                    io.github.xblocker.download.MediaDownload.enqueueBatch(ctx, folder, items, batchId)
                } finally { Binder.restoreCallingIdentity(identity) }
                history.setTotal(batchId, result.queued)
                if (result.queued == 0) history.remove(batchId)
                Bundle().apply {
                    putInt("queued", result.queued); putInt("rejected", result.rejected)
                    putInt("skipped", result.skipped); putString("folder", result.folder)
                }
            }
            "snapshot" -> Bundle().apply { putString("json", repository.snapshot()) }
            "report" -> {
                val payload = extras?.getString("json").orEmpty()
                require(payload.length <= 150_000)
                val report = JSONObject(payload)
                repository.report(report)
                UpdateActivationStore(ctx).observeHost(report)
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
