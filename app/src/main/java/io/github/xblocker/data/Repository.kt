package io.github.xblocker.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import io.github.xblocker.BuildConfig
import java.io.File
import io.github.xblocker.core.ConfigCodec
import io.github.xblocker.core.FilterSettings
import org.json.JSONArray
import org.json.JSONObject

class Repository(context: Context) {
    private val appContext = context.applicationContext
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
            makeSharedPrefsReadable()
        }
    }
    fun settings(): FilterSettings = ConfigCodec.decode(JSONObject(prefs.getString("settings", "{}")!!))
    fun save(settings: FilterSettings) { synchronized(lock) {
        check(prefs.edit().putString("settings", ConfigCodec.encode(settings).toString()).commit())
        makeSharedPrefsReadable()
    } }
    fun cloud(): String = prefs.getString("cloud", "")!!
    fun snapshot(): String = JSONObject().put("settings", ConfigCodec.encode(settings())).put("cloud", cloud())
        .put("fluidCloud", fluidCloud()).put("focusNotification", focusNotification()).toString()
    fun lastSync(): Long = prefs.getLong("lastSync", 0)
    fun syncError(): String = prefs.getString("syncError", "")!!
    fun etag(): String = prefs.getString("etag", "")!!
    fun synced(text: String?, etag: String) { synchronized(lock) {
        val edit = prefs.edit().putLong("lastSync", System.currentTimeMillis()).putString("syncError", "").putString("etag", etag)
        if (text != null) edit.putString("cloud", text)
        check(edit.commit())
        makeSharedPrefsReadable()
    } }
    fun syncFailed(message: String) { commitAndShare(prefs.edit().putString("syncError", message)) }
    /** One-shot post-install guide for the OEM autostart / live-activity switches. */
    fun needsOemGuide(): Boolean = !prefs.getBoolean("oemGuideShown", false)
    fun markOemGuideShown() { commitAndShare(prefs.edit().putBoolean("oemGuideShown", true)) }
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
        commitAndShare(prefs.edit().putString("diagnostics", previous.toString()).putString("history", records.toString())
            .putLong("blocked", blocked() + added))
        makeSharedPrefsReadable()
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
        commitAndShare(prefs.edit().putString("marker", marker.toString()))
    } }

    /** System-side hook presence markers, keyed "hookMarker.<phase>" — proof a scope is active. */
    fun reportHookMarker(payload: JSONObject) { synchronized(lock) {
        val phase = payload.optString("phase")
        if (phase.isBlank()) return
        payload.put("lastSeen", System.currentTimeMillis())
        commitAndShare(prefs.edit().putString("hookMarker.$phase", payload.toString()))
    } }
    fun hookMarker(phase: String): JSONObject? =
        prefs.getString("hookMarker.$phase", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Version code the fluid-cloud scope prompt was last shown for; 0 = never. */
    fun scopePromptVersion(): Int = prefs.getInt("scopePromptVersion", 0)
    fun setScopePromptShown(version: Int) { commitAndShare(prefs.edit().putInt("scopePromptVersion", version)) }
    fun clearHistory() { synchronized(lock) { commitAndShare(prefs.edit().remove("history").remove("blocked")) } }
    fun fluidCloud(): Boolean = prefs.getBoolean("fluidCloud", false)
    fun setFluidCloud(enabled: Boolean) {
        check(prefs.edit().putBoolean("fluidCloud", enabled).commit()); makeSharedPrefsReadable()
        createChannelsIfEnabled()
    }
    fun focusNotification(): Boolean = prefs.getBoolean("focusNotification", false)
    fun setFocusNotification(enabled: Boolean) {
        check(prefs.edit().putBoolean("focusNotification", enabled).commit()); makeSharedPrefsReadable()
        createChannelsIfEnabled()
    }

    /**
     * The live notifications are normally posted by X's process, which never creates
     * channels under this package; without this the system's per-app notification page
     * shows "no channels" and OEM focus-permission UI has nothing to attach to.
     */
    fun createChannelsIfEnabled() {
        runCatching {
            if (fluidCloud()) io.github.xblocker.fluid.FluidStatus.ensureChannels(appContext)
            if (focusNotification()) io.github.xblocker.fluid.FocusStatus.ensureChannels(appContext)
        }
    }
    fun autoUpdate(): Boolean = prefs.getBoolean("autoUpdate", true)
    fun setAutoUpdate(enabled: Boolean) { check(prefs.edit().putBoolean("autoUpdate", enabled).commit()); makeSharedPrefsReadable() }
    /** Update channel: 0 = stable only, 1 = include pre-releases. */
    fun updateChannel(): Int = prefs.getInt("updateChannel", 0).coerceIn(0, 1)
    fun setUpdateChannel(channel: Int) { check(prefs.edit().putInt("updateChannel", channel.coerceIn(0, 1)).commit()); makeSharedPrefsReadable() }
    /** SuKIsu-style color mode: 0 system, 1 light, 2 dark, 3-5 the Monet variants. */
    fun colorMode(): Int = prefs.getInt("colorMode", 0)
    fun setColorMode(mode: Int) { check(prefs.edit().putInt("colorMode", mode).commit()); makeSharedPrefsReadable() }
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
        makeSharedPrefsReadable()
    }
    /**
     * LSPosed's shared-preferences redirect recreates the prefs file with mode 0660 on every
     * write, so an async apply() would race the chmod and lock the X process out of the
     * fallback again. Commit synchronously, then re-share.
     */
    private fun commitAndShare(edit: android.content.SharedPreferences.Editor) {
        edit.commit()
        makeSharedPrefsReadable()
    }

    /** LSPosed's shared-preferences bridge may leave the redirected file at mode 0660. */
    @Volatile private var sharedPrefsFile: File? = null
    private fun makeSharedPrefsReadable() {
        sharedPrefsFile?.let { file -> runCatching { file.setReadable(true, false) }; return }
        runCatching {
            val fields = buildList {
                var type: Class<*>? = prefs.javaClass
                while (type != null) {
                    addAll(type.declaredFields.filter { File::class.java.isAssignableFrom(it.type) })
                    type = type.superclass
                }
            }
            for (field in fields) runCatching {
                field.isAccessible = true
                val file = field.get(prefs) as? File ?: return@runCatching
                if (!file.exists()) return@runCatching
                sharedPrefsFile = file
                file.setReadable(true, false)
                Log.d("XBlockerPrefs", "${field.name}: ${file.absolutePath} mode=${filePerms(file)}")
            }.onFailure { Log.d("XBlockerPrefs", "${field.name}: ${it.javaClass.simpleName}: ${it.message}") }
        }.onFailure { Log.d("XBlockerPrefs", "scan failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun filePerms(file: File): String = runCatching {
        val mode = file.toPath().let { java.nio.file.Files.getPosixFilePermissions(it) }
        mode.joinToString(",")
    }.getOrDefault("unknown")
    companion object { private val lock = Any() }
}
