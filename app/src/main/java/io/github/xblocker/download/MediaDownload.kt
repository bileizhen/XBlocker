package io.github.xblocker.download

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Called only after the provider authenticated X's UID and cleared its Binder identity. */
object MediaDownload {
    data class Result(val queued: Boolean, val requestId: String?, val message: String)

    @Synchronized
    fun enqueue(context: Context, url: String, tweetId: String, mediaId: String, type: String): Result {
        if (Build.VERSION.SDK_INT < 29) return Result(false, null, "Android 10 or later is required to save to Download/X without storage permission")
        return try {
            val kind = MediaDownloadPolicy.kind(type)
            MediaDownloadPolicy.validateUrl(url, kind)
            MediaDownloadPolicy.validateId(tweetId)
            MediaDownloadPolicy.validateId(mediaId)
            val manager = WorkManager.getInstance(context.applicationContext)
            val name = "x-media:${MediaDownloadPolicy.key(url, tweetId, mediaId)}"
            // Avoid repeated taps creating duplicate downloads while one is queued or running.
            val existing = manager.getWorkInfosForUniqueWork(name).get(3, TimeUnit.SECONDS)
                .firstOrNull { !it.state.isFinished }
            if (existing != null) return Result(true, existing.id.toString(), "Already queued")
            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("url" to url, "tweetId" to tweetId, "mediaId" to mediaId, "type" to type))
                .addTag("xblocker-media-download")
                .build()
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get(5, TimeUnit.SECONDS)
            Result(true, request.id.toString(), "Queued for Download/X/")
        } catch (e: IllegalArgumentException) {
            Result(false, null, e.message ?: "Invalid media request")
        } catch (_: Exception) {
            Result(false, null, "Could not queue media download")
        }
    }

    /** Diagnostic and UI entry point. Returns persisted WorkManager output, including saved URI on success. */
    fun status(context: Context, requestId: String): Map<String, Any?> = runCatching {
        val info = WorkManager.getInstance(context.applicationContext).getWorkInfoById(UUID.fromString(requestId))
            .get(3, TimeUnit.SECONDS)
        if (info == null) emptyMap() else mapOf("state" to info.state.name, "uri" to info.outputData.getString("uri"),
            "message" to info.outputData.getString("message"), "bytes" to info.outputData.getLong("bytes", 0),
            "downloaded" to info.progress.getLong("downloaded", 0))
    }.getOrDefault(emptyMap())
}
