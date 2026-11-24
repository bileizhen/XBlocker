package io.github.xblocker.download

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Called only after the provider authenticated X's UID and cleared its Binder identity. */
object MediaDownload {
    data class Result(val queued: Boolean, val requestId: String?, val message: String,
                         val alreadyQueued: Boolean = false)
    data class BatchResult(val queued: Int, val rejected: Int, val skipped: Int, val folder: String)

    @Synchronized
    fun enqueue(context: Context, url: String, tweetId: String, mediaId: String, type: String, folder: String? = null,
                batchId: String? = null): Result {
        if (Build.VERSION.SDK_INT < 29) return Result(false, null, "Android 10 or later is required to save to Download/X without storage permission")
        return try {
            val kind = MediaDownloadPolicy.kind(type)
            MediaDownloadPolicy.validateUrl(url, kind)
            MediaDownloadPolicy.validateId(tweetId)
            MediaDownloadPolicy.validateId(mediaId)
            val safeFolder = MediaDownloadPolicy.folder(folder)
            val destination = MediaDownloadPolicy.relativePath(safeFolder)
            val manager = WorkManager.getInstance(context.applicationContext)
            val name = workName(url, tweetId, mediaId, safeFolder)
            // Avoid repeated taps creating duplicate downloads while one is queued or running.
            val existing = manager.getWorkInfosForUniqueWork(name).get(3, TimeUnit.SECONDS)
                .firstOrNull { !it.state.isFinished }
            if (existing != null) return Result(true, existing.id.toString(), "Already queued", alreadyQueued = true)
            val request = workRequest(url, tweetId, mediaId, type, safeFolder, batchId)
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get(5, TimeUnit.SECONDS)
            Result(true, request.id.toString(), "Queued for $destination")
        } catch (e: IllegalArgumentException) {
            Result(false, null, e.message ?: "Invalid media request")
        } catch (_: Exception) {
            Result(false, null, "Could not queue media download")
        }
    }

    /**
     * Bulk queue for the profile downloader. Validates each item with the same policy as a single
     * download but skips the per-item WorkManager status queries, which would dominate a large run.
     */
    @Synchronized
    fun enqueueBatch(context: Context, folder: String?, itemsJson: String, batchId: String? = null): BatchResult {
        if (Build.VERSION.SDK_INT < 29) return BatchResult(0, 0, 0, "")
        val safeFolder = runCatching { MediaDownloadPolicy.folder(folder) }.getOrDefault("")
        val array = runCatching { JSONArray(itemsJson) }.getOrDefault(JSONArray())
        val manager = WorkManager.getInstance(context.applicationContext)
        val ledger = MediaDownloadLedger(context)
        // Already saved in this folder (ledger plus existing files, which predate the ledger).
        val known = ledger.keys(safeFolder).apply { addAll(existingKeys(context, safeFolder)) }
        val saved = mutableListOf<String>()
        var queued = 0
        var rejected = 0
        var skipped = 0
        val limit = minOf(array.length(), 1000)
        for (index in 0 until limit) {
            val item = array.optJSONObject(index)
            if (item == null) { rejected++; continue }
            val parsed = runCatching {
                val type = item.optString("type")
                val kind = MediaDownloadPolicy.kind(type)
                arrayOf(type, MediaDownloadPolicy.validateUrl(item.optString("url"), kind).toString(),
                    MediaDownloadPolicy.validateId(item.optString("tweetId")),
                    MediaDownloadPolicy.validateId(item.optString("mediaId")))
            }.getOrNull() ?: run { rejected++; continue }
            val key = ledger.key(parsed[2], parsed[3])
            // Check the saved-media ledger before touching WorkManager, otherwise every repeat
            // run enqueues the same files again even though it reports them as skipped.
            if (key in known) { skipped++; continue }
            val accepted = runCatching {
                val request = workRequest(parsed[1], parsed[2], parsed[3], parsed[0], safeFolder, batchId)
                manager.enqueueUniqueWork(workName(parsed[1], parsed[2], parsed[3], safeFolder),
                    ExistingWorkPolicy.KEEP, request)
            }.isSuccess
            if (accepted) { queued++; saved += key } else rejected++
        }
        ledger.record(safeFolder, known + saved)
        return BatchResult(queued, rejected, skipped, safeFolder)
    }

    /** File names are X_<tweetId>_<mediaId>_<workId>.<ext>, so saved media can be recognised. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun existingKeys(context: Context, folder: String): Set<String> = runCatching {
        if (folder.isEmpty()) return emptySet()
        val path = MediaDownloadPolicy.relativePath(folder)
        context.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads.DISPLAY_NAME),
            "${android.provider.MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(path), null)?.use { cursor ->
            val keys = mutableSetOf<String>()
            val pattern = Regex("^X_(\\d{1,32})_(\\d{1,32})_")
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                pattern.find(name)?.let { keys += "${it.groupValues[1]}_${it.groupValues[2]}" }
            }
            keys
        } ?: emptySet()
    }.getOrDefault(emptySet())

    private fun workRequest(url: String, tweetId: String, mediaId: String, type: String,
                            folder: String, batchId: String? = null) = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInputData(workDataOf("url" to url, "tweetId" to tweetId, "mediaId" to mediaId,
            "type" to type, "folder" to folder, "batchId" to batchId))
        .addTag("xblocker-media-download")
        .build()

    private fun workName(url: String, tweetId: String, mediaId: String, folder: String) =
        "x-media:${MediaDownloadPolicy.key(url, tweetId, mediaId)}:${folder.hashCode()}"

    /** Diagnostic and UI entry point. Returns persisted WorkManager output, including saved URI on success. */
    fun status(context: Context, requestId: String): Map<String, Any?> = runCatching {
        val info = WorkManager.getInstance(context.applicationContext).getWorkInfoById(UUID.fromString(requestId))
            .get(3, TimeUnit.SECONDS)
        if (info == null) emptyMap() else mapOf("state" to info.state.name, "uri" to info.outputData.getString("uri"),
            "message" to info.outputData.getString("message"), "bytes" to info.outputData.getLong("bytes", 0),
            "downloaded" to info.progress.getLong("downloaded", 0))
    }.getOrDefault(emptyMap())
}
