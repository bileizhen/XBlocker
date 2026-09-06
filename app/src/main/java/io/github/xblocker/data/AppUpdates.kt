package io.github.xblocker.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.xblocker.BuildConfig
import io.github.xblocker.core.AppRelease
import io.github.xblocker.core.ReleaseParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

enum class UpdateSource(val label: String, private val prefix: String = "") {
    GITHUB("GitHub 原站"),
    GH_DPIK_TOP("gh.dpik.top 镜像", "https://gh.dpik.top/"),
    ;

    fun url(release: AppRelease): String = prefix + release.downloadUrl
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val source: UpdateSource, val received: Long, val total: Long) : UpdateDownloadState
    data class Ready(val source: UpdateSource, val file: File) : UpdateDownloadState
    data class Failed(val source: UpdateSource, val reason: String) : UpdateDownloadState
}

enum class InstallResult { Started, PermissionRequired }

object AppUpdates {
    private const val MAX_APK_BYTES = 128L * 1024L * 1024L

    fun check(): AppRelease? {
        val connection = URI("https://api.github.com/repos/bileizhen/XBlocker/releases/latest").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "XBlocker/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 256 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 256 * 1024) { "更新信息过大" }
            return ReleaseParser.newerRelease(bytes.toString(Charsets.UTF_8), BuildConfig.VERSION_NAME)
        } finally { connection.disconnect() }
    }

    fun download(context: Context, release: AppRelease, source: UpdateSource, onProgress: (received: Long, total: Long) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val stem = "XBlocker-${release.version}-${source.name.lowercase()}"
        val target = File(directory, "$stem.apk")
        val temporary = File(directory, "$stem.apk.part")
        if (target.isFile && target.length() > 0L) {
            onProgress(target.length(), target.length())
            return target
        }
        temporary.delete()
        val connection = URI(source.url(release)).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "XBlocker/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            check(total <= MAX_APK_BYTES || total < 0L) { "APK 文件过大" }
            var received = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= MAX_APK_BYTES) { "APK 文件过大" }
                        output.write(buffer, 0, count)
                        onProgress(received, total)
                    }
                }
            }
            check(received > 0L) { "下载内容为空" }
            check(temporary.renameTo(target)) { "无法保存更新文件" }
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun install(context: Context, file: File): InstallResult {
        check(file.isFile && file.length() > 0L) { "更新文件不存在" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return InstallResult.PermissionRequired
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return InstallResult.Started
    }
}
