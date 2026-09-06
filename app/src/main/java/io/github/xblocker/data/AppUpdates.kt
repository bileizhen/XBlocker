package io.github.xblocker.data

import io.github.xblocker.BuildConfig
import io.github.xblocker.core.AppRelease
import io.github.xblocker.core.ReleaseParser
import java.net.HttpURLConnection
import java.net.URI

object AppUpdates {
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
}
