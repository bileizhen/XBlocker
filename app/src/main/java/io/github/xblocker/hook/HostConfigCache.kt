package io.github.xblocker.hook

import android.content.Context
import io.github.xblocker.core.ConfigCodec
import org.json.JSONObject

/** FPA fallback lives in X's own private storage; never depends on chmod or LSPosed. */
internal class HostConfigCache(context: Context) {
    private val prefs = context.getSharedPreferences("xblocker_fpa_config", Context.MODE_PRIVATE)

    fun read(): String? = prefs.getString("snapshot", null)?.takeIf(::valid)

    fun save(snapshot: String) {
        require(valid(snapshot)) { "Invalid XBlocker configuration" }
        check(prefs.edit().putString("snapshot", snapshot).commit())
    }

    private fun valid(snapshot: String): Boolean = snapshot.length <= 1_000_000 && runCatching {
        val json = JSONObject(snapshot)
        ConfigCodec.decode(json.getJSONObject("settings"))
        json.opt("cloud") is String
    }.getOrDefault(false)
}
