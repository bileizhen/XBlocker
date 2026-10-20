package io.github.xblocker.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/** Scoped-storage writer. Never sends X cookies, authorization headers, or arbitrary URLs. */
class MediaDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val prefs = context.getSharedPreferences("media_download_pending", Context.MODE_PRIVATE)
    private val chinese get() = applicationContext.resources.configuration.locales[0].language == "zh"
    private fun text(zh: String, en: String) = if (chinese) zh else en

    override fun doWork(): Result {
        if (Build.VERSION.SDK_INT < 29) return Result.failure(workDataOf("message" to "Android 10 or later is required"))
        return download()
    }

    @RequiresApi(29)
    private fun download(): Result {
        var pending: Uri? = null
        var connection: HttpsURLConnection? = null
        val resolver = applicationContext.contentResolver
        val started = SystemClock.elapsedRealtime()
        try {
            // A previous process may have died after inserting an unpublished row. A retry owns the same work ID.
            prefs.getString(id.toString(), null)?.let { old ->
                val oldUri = Uri.parse(old)
                resolver.query(oldUri, arrayOf(MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE), null, null, null)?.use { cursor ->
                    // The process may also have died immediately after publishing. Preserve that completed file.
                    if (cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        prefs.edit().remove(id.toString()).commit()
                        return Result.success(workDataOf("uri" to old, "bytes" to cursor.getLong(1), "message" to "Saved to Download/X/"))
                    }
                }
                resolver.delete(oldUri, null, null)
                prefs.edit().remove(id.toString()).commit()
            }
            val type = inputData.getString("type") ?: throw IllegalArgumentException("Missing media type")
            val kind = MediaDownloadPolicy.kind(type)
            val tweetId = MediaDownloadPolicy.validateId(inputData.getString("tweetId").orEmpty())
            val mediaId = MediaDownloadPolicy.validateId(inputData.getString("mediaId").orEmpty())
            var target = MediaDownloadPolicy.validateUrl(inputData.getString("url").orEmpty(), kind)
            var redirects = 0
            while (true) {
                checkRunning(started)
                val conn = target.toURL().openConnection() as HttpsURLConnection
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("User-Agent", "XBlocker-MediaDownload")
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    require(redirects++ < 5) { "Too many media redirects" }
                    val location = conn.getHeaderField("Location") ?: throw IOException("Missing redirect target")
                    val next: URI = target.resolve(location)
                    target = MediaDownloadPolicy.validateUrl(next.toASCIIString(), kind)
                    conn.disconnect()
                    connection = null
                    continue
                }
                if (code != HttpsURLConnection.HTTP_OK) throw IOException("Media server returned HTTP $code")
                break
            }
            val conn = connection
            val mime = MediaDownloadPolicy.contentType(conn.contentType, kind)
            val length = conn.contentLengthLong
            require(length <= kind.maxBytes) { "Media exceeds download size limit" }
            val filename = "X_${tweetId}_${mediaId}_${id}.${MediaDownloadPolicy.extension(mime)}"
            BufferedInputStream(conn.inputStream).use { input ->
                val header = ByteArray(12)
                var count = 0
                while (count < header.size) {
                    val read = input.read(header, count, header.size - count)
                    if (read < 0) break
                    count += read
                }
                MediaDownloadPolicy.verifyHeader(header, count, mime)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/X/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    put(MediaStore.Downloads.DATE_EXPIRES, System.currentTimeMillis() / 1000 + 24 * 60 * 60)
                }
                pending = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create Download/X file")
                require(prefs.edit().putString(id.toString(), pending.toString()).commit()) { "Could not record pending download" }
                var total = count.toLong()
                var lastProgress = 0L
                resolver.openOutputStream(pending, "w")?.use { output ->
                    output.write(header, 0, count)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkRunning(started)
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= kind.maxBytes) { "Media exceeds download size limit" }
                        output.write(buffer, 0, read)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 1000) {
                            lastProgress = now
                            setProgressAsync(workDataOf("downloaded" to total))
                            notifyDownload(filename, total, length)
                        }
                    }
                    output.flush()
                } ?: throw IOException("Could not open Download/X file")
                checkRunning(started)
                require(length < 0 || total == length) { "Incomplete media download" }
                val uri = pending
                val published = resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    putNull(MediaStore.Downloads.DATE_EXPIRES)
                }, null, null)
                require(published == 1) { "Could not publish media download" }
                pending = null
                prefs.edit().remove(id.toString()).commit()
                notifyDownload(filename, total, total, uri, mime)
                return Result.success(workDataOf("uri" to uri.toString(), "bytes" to total,
                    "message" to "Saved to Download/X/"))
            }
        } catch (e: Exception) {
            clearNotification()
            if (e is CancellationException || isStopped) throw CancellationException("Media download cancelled")
            // Retry transient network errors twice. Validation failures are final.
            if (e is IOException && runAttemptCount < 2) return Result.retry()
            val message = (e.message ?: "Media download failed").take(200)
            notifyFailure(message)
            return Result.failure(workDataOf("message" to message))
        } finally {
            connection?.disconnect()
            pending?.let { uri ->
                if (runCatching { resolver.delete(uri, null, null) }.isSuccess) prefs.edit().remove(id.toString()).commit()
            }
        }
    }

    private fun checkRunning(started: Long) {
        if (isStopped) throw CancellationException("Media download cancelled")
        if (SystemClock.elapsedRealtime() - started > 8 * 60 * 1000L) throw IOException("Media download timed out")
    }

    private fun clearNotification() {
        applicationContext.getSystemService(NotificationManager::class.java).cancel(id.toString(), 1)
    }

    private fun notifyFailure(message: String) {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("media_downloads", text("媒体下载", "Media downloads"), NotificationManager.IMPORTANCE_LOW))
            manager.notify(id.toString(), 1, Notification.Builder(applicationContext, "media_downloads")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(text("媒体下载失败", "Media download failed"))
                .setContentText(message).setAutoCancel(true).build())
        }
    }

    private fun notifyDownload(filename: String, bytes: Long, length: Long, uri: Uri? = null, mime: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("media_downloads", text("媒体下载", "Media downloads"), NotificationManager.IMPORTANCE_LOW))
            val builder = Notification.Builder(applicationContext, "media_downloads")
                .setSmallIcon(if (uri == null) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
                .setContentTitle(text(if (uri == null) "正在下载媒体" else "已保存到 Download/X/", if (uri == null) "Downloading media" else "Saved to Download/X/"))
                .setContentText(filename).setOnlyAlertOnce(true).setOngoing(uri == null).setAutoCancel(uri != null)
            if (uri == null) builder.setProgress(100, if (length > 0) (bytes * 100 / length).coerceAtMost(100).toInt() else 0, length <= 0)
            else {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                builder.setContentIntent(PendingIntent.getActivity(applicationContext, id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            manager.notify(id.toString(), 1, builder.build())
        }
    }
}
