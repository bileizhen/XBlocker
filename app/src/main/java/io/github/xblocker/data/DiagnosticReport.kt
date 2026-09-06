package io.github.xblocker.data

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.xblocker.BuildConfig
import io.github.xblocker.ui.UiState
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** XBlocker adaptation of SukiSU's getBugreportFile: no root or storage permission required. */
object DiagnosticReport {
    fun create(context: Context, state: UiState): File {
        val directory = File(context.cacheDir, "bugreports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm_ss"))
        val report = File(directory, "XBlocker_bugreport_${stamp}_${UUID.randomUUID().toString().take(8)}.zip")
        val xPackage = runCatching { context.packageManager.getPackageInfo("com.twitter.android", 0) }.getOrNull()
        val xName = xPackage?.versionName
        val summary = JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("createdAt", System.currentTimeMillis())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("xVersion", when { xPackage == null -> "未安装"; xName.isNullOrBlank() -> "未知"; else -> xName })
            put("xVersionCode", xPackage?.longVersionCode ?: 0L)
            put("configurationReady", state.ready)
            put("ruleCount", state.engine.count)
            put("rejectedRuleCount", state.engine.rejected.size)
            put("blocked", state.blocked)
            put("lastSync", state.lastSync)
            put("syncError", state.syncError.take(2000))
            put("enabled", state.settings.enabled)
            put("onlyReplies", state.settings.onlyReplies)
            put("checkNames", state.settings.checkNames)
            put("blockPromoted", state.settings.blockPromoted)
            put("cloudEnabled", state.settings.cloudEnabled)
            put("fluidCloud", state.fluidCloud)
            put("colorMode", state.colorMode)
            put("blur", state.appearance.blur)
            put("floatingBar", state.appearance.floatingBar)
            put("liquidGlass", state.appearance.liquidGlass)
            put("predictiveBack", state.appearance.predictiveBack)
            put("scale", state.appearance.scale)
        }
        // Keep diagnostics explicit: never export stored rules, whitelist or tweet history.
        val diagnostics = JSONObject().apply {
            for (key in listOf("lastSeen", "pid", "adapter", "responses", "seen", "filtered", "error")) {
                if (state.diagnostics.has(key)) put(key, state.diagnostics.get(key))
            }
        }
        // Activation marker arrives via broadcast from the X process even when the provider
        // bridge is down; whitelisted keys only, error text truncated like diagnostics above.
        val marker = JSONObject().apply {
            for (key in listOf("firstSeen", "lastSeen", "pid", "version", "phase", "fallback", "hooks", "adapter")) {
                if (state.marker.has(key)) put(key, state.marker.get(key))
            }
            if (state.marker.optString("error").isNotBlank()) put("error", state.marker.optString("error").take(200))
        }
        try {
            ZipOutputStream(report.outputStream().buffered()).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("README.txt", "XBlocker diagnostic report\n\nsummary.json: app, device, installed X version, module activation marker and feature status.\ndiagnostics.json: module connection and filtering counters.\nlogcat.txt: recent logs visible to the XBlocker app process.\n\nNo root access is requested. X process / LSPosed private logs are not available to this app.\nCustom rules, whitelist and tweet history are not included.\n")
                entry("summary.json", summary.put("moduleMarker", marker).toString(2))
                entry("diagnostics.json", diagnostics.toString(2))
                entry("logcat.txt", captureLogcat(context))
            }
            // Retain shared files for a day so recipients can finish reading their granted URI.
            directory.listFiles()?.filter {
                it.isFile && it.name.startsWith("XBlocker_bugreport_") && it.extension == "zip" &&
                    it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            }?.forEach { it.delete() }
            return report
        } catch (error: Exception) {
            report.delete()
            throw error
        }
    }

    private fun captureLogcat(context: Context): String {
        val output = File.createTempFile("xblocker-logcat-", ".txt", context.cacheDir)
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "500")
                .redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            val text = output.bufferedReader().use { reader ->
                val chars = CharArray(2_000_000)
                var count = 0
                while (count < chars.size) {
                    val read = reader.read(chars, count, chars.size - count)
                    if (read < 0) break
                    count += read
                }
                String(chars, 0, count)
            }
            (if (!finished) "Log collection timed out; partial output follows.\n" else "") +
                text.ifBlank { "No app logcat entries were available.\n" }
        } catch (error: Exception) {
            "App logcat unavailable: ${error.javaClass.simpleName}\n"
        } finally {
            process?.destroy()
            output.delete()
        }
    }
}
