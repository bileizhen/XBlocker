package io.github.xblocker

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.core.FilterSettings
import io.github.xblocker.data.DiagnosticReport
import io.github.xblocker.ui.UiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DiagnosticReportTest {
    @Test fun exportedZipContainsDiagnosticsWithoutUserRulesOrHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = DiagnosticReport.create(context, UiState(
            ready = true,
            settings = FilterSettings(customRules = "private-rule-sentinel", whitelist = setOf("private-account-sentinel")),
            diagnostics = JSONObject().put("adapter", "test-adapter").put("filtered", 7).put("unexpected", "private-field-sentinel"),
            marker = JSONObject().put("lastSeen", 123L).put("phase", "bridge-failed").put("fallback", true).put("error", "marker-error-sentinel").put("unexpected", "private-marker-sentinel"),
            history = listOf(JSONObject().put("text", "private-history-sentinel")),
        ))
        try {
            ZipFile(report).use { zip ->
                val entries = zip.entries().toList()
                assertEquals(setOf("README.txt", "summary.json", "diagnostics.json", "logcat.txt"), entries.map { it.name }.toSet())
                val texts = entries.associate { it.name to zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                val diagnostics = JSONObject(texts.getValue("diagnostics.json"))
                assertEquals("test-adapter", diagnostics.getString("adapter"))
                assertEquals(7, diagnostics.getInt("filtered"))
                val summary = JSONObject(texts.getValue("summary.json"))
                assertTrue(summary.getBoolean("configurationReady"))
                assertTrue(summary.has("xVersion"))
                val marker = summary.getJSONObject("moduleMarker")
                assertEquals(123L, marker.getLong("lastSeen"))
                assertEquals("bridge-failed", marker.getString("phase"))
                assertEquals("marker-error-sentinel", marker.getString("error"))
                assertFalse(marker.has("unexpected"))
                val content = texts.values.joinToString()
                for (secret in listOf("private-rule-sentinel", "private-account-sentinel", "private-field-sentinel", "private-history-sentinel", "private-marker-sentinel")) {
                    assertFalse("Export contains $secret", content.contains(secret))
                }
                assertTrue(texts.getValue("logcat.txt").isNotBlank())
            }
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", report)
            assertEquals("content", uri.scheme)
            assertEquals("application/zip", context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri)!!.use { assertEquals('P'.code, it.read()); assertEquals('K'.code, it.read()) }
        } finally { report.delete() }
    }
}
