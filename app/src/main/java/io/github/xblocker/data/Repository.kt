package io.github.xblocker.data

import android.content.Context
import android.util.AtomicFile
import io.github.xblocker.BuildConfig
import java.io.File
import io.github.xblocker.core.ConfigCodec
import io.github.xblocker.core.FilterSettings
import org.json.JSONArray
import org.json.JSONObject

class Repository(context: Context) {
    // With xposedsharedprefs declared, LSPosed redirects the module app's prefs dir to its
    // serving path and force-chmods files to 744 there (ConfigCache.getPrefsPath), which is
    // what XSharedPreferences in X reads. The redirect depends on the meta-data, not on the
    // mode passed here — MODE_PRIVATE avoids the SecurityException that MODE_WORLD_READABLE
    // would throw once the framework drops its checkMode hook (New XSharedPreferences is
    // scheduled for removal in LSPosed 2.3.0).
    private val prefs = context.getSharedPreferences("xblocker", Context.MODE_PRIVATE)
    private val debugStatus = File(context.filesDir, "bridge-status.json")
    init {
        synchronized(lock) {
            if (!prefs.contains("cloud")) prefs.edit().putString("cloud", context.assets.open("keywords.txt").bufferedReader().use { it.readText() }).commit()
        }
    }
    fun settings(): FilterSettings = ConfigCodec.decode(JSONObject(prefs.getString("settings", "{}")!!))
    fun save(settings: FilterSettings) { synchronized(lock) { check(prefs.edit().putString("settings", ConfigCodec.encode(settings).toString()).commit()) } }
    fun cloud(): String = prefs.getString("cloud", "")!!
    fun snapshot(): String = JSONObject().put("settings", ConfigCodec.encode(settings())).put("cloud", cloud()).toString()
    fun lastSync(): Long = prefs.getLong("lastSync", 0)
    fun syncError(): String = prefs.getString("syncError", "")!!
    fun etag(): String = prefs.getString("etag", "")!!
    fun synced(text: String?, etag: String) { synchronized(lock) {
        val edit = prefs.edit().putLong("lastSync", System.currentTimeMillis()).putString("syncError", "").putString("etag", etag)
        if (text != null) edit.putString("cloud", text)
        check(edit.commit())
    } }
    fun syncFailed(message: String) { prefs.edit().putString("syncError", message).apply() }
    fun diagnostics(): JSONObject = JSONObject(prefs.getString("diagnostics", "{}")!!)
    fun report(json: JSONObject) { synchronized(lock) {
        val previous = diagnostics()
        for (key in json.keys()) if (key in setOf("version", "hooks", "adapter", "pid", "fg", "seen", "filtered", "error", "responses", "rules", "rejected", "probe",
                "envelopes", "jsonParsed", "entriesArrays", "entriesInspected", "tweets", "texts", "replies", "promoted", "blocked")) previous.put(key, json.opt(key))
        previous.put("lastSeen", System.currentTimeMillis())
        val records = history()
        val incoming = json.optJSONArray("events") ?: JSONArray()
        val known = (0 until records.length()).map { records.getJSONObject(it).optString("id") }.toMutableSet()
        var added = 0
        for (i in 0 until minOf(incoming.length(), 100)) {
            val event = incoming.optJSONObject(i) ?: continue
            if (event.optString("id").isBlank() || !known.add(event.optString("id"))) continue
            val safe = JSONObject()
            for (key in listOf("id", "handle", "reason", "rule", "category")) safe.put(key, event.optString(key).take(1000))
            safe.put("time", System.currentTimeMillis())
            records.put(safe); added++
        }
        while (records.length() > 200) records.remove(0)
        prefs.edit().putString("diagnostics", previous.toString()).putString("history", records.toString())
            .putLong("blocked", blocked() + added).apply()
        if (BuildConfig.DEBUG) {
            val file = AtomicFile(debugStatus)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                // The structural fingerprint is schema-only and kept out of persisted prefs.
                previous.put("fp", json.optJSONArray("fp") ?: JSONArray())
                stream.write(previous.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (_: Exception) { file.failWrite(stream) }
        }
    } }
    fun blocked(): Long = prefs.getLong("blocked", 0)
    fun history(): JSONArray = JSONArray(prefs.getString("history", "[]")!!)
    fun marker(): JSONObject = JSONObject(prefs.getString("marker", "{}")!!)
    fun reportMarker(payload: JSONObject) { synchronized(lock) {
        val marker = marker()
        if (!marker.has("firstSeen")) marker.put("firstSeen", System.currentTimeMillis())
        marker.put("lastSeen", System.currentTimeMillis())
        for (key in listOf("pid", "version", "phase", "fallback", "hooks", "adapter", "error")) if (payload.has(key)) marker.put(key, payload.opt(key))
        prefs.edit().putString("marker", marker.toString()).apply()
    } }
    fun clearHistory() { synchronized(lock) { prefs.edit().remove("history").remove("blocked").apply() } }
    fun fluidCloud(): Boolean = prefs.getBoolean("fluidCloud", false)
    fun setFluidCloud(enabled: Boolean) { prefs.edit().putBoolean("fluidCloud", enabled).commit() }
    /** SuKIsu-style color mode: 0 system, 1 light, 2 dark, 3-5 the Monet variants. */
    fun colorMode(): Int = prefs.getInt("colorMode", 0)
    fun setColorMode(mode: Int) { prefs.edit().putInt("colorMode", mode).commit() }
    fun appearance() = io.github.xblocker.ui.AppearanceSettings(
        blur = prefs.getBoolean("ui.blur", true),
        floatingBar = prefs.getBoolean("ui.floatingBar", true),
        liquidGlass = prefs.getBoolean("ui.liquidGlass", true),
        predictiveBack = prefs.getBoolean("ui.predictiveBack", true),
        scale = prefs.getFloat("ui.scale", 1f).coerceIn(0.8f, 1.1f),
    )
    fun setAppearance(value: io.github.xblocker.ui.AppearanceSettings) {
        check(prefs.edit().putBoolean("ui.blur", value.blur)
            .putBoolean("ui.floatingBar", value.floatingBar)
            .putBoolean("ui.liquidGlass", value.liquidGlass)
            .putBoolean("ui.predictiveBack", value.predictiveBack)
            .putFloat("ui.scale", value.scale.coerceIn(0.8f, 1.1f)).commit())
    }
    companion object { private val lock = Any() }
}
