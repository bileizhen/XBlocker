package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject

object ConfigCodec {
    fun encode(settings: FilterSettings): JSONObject = JSONObject().apply {
        put("enabled", settings.enabled); put("cloudEnabled", settings.cloudEnabled)
        put("onlyReplies", settings.onlyReplies); put("checkNames", settings.checkNames)
        put("blockPromoted", settings.blockPromoted); put("customRules", settings.customRules)
        put("disabledCategories", JSONArray(settings.disabledCategories.toList()))
        put("whitelist", JSONArray(settings.whitelist.toList()))
    }
    fun decode(json: JSONObject): FilterSettings = FilterSettings(
        enabled = json.optBoolean("enabled", true), cloudEnabled = json.optBoolean("cloudEnabled", true),
        onlyReplies = json.optBoolean("onlyReplies", true), checkNames = json.optBoolean("checkNames", true),
        blockPromoted = json.optBoolean("blockPromoted", true), customRules = json.optString("customRules"),
        disabledCategories = if (json.has("disabledCategories")) strings(json.optJSONArray("disabledCategories")) else setOf("仇恨用语"),
        whitelist = strings(json.optJSONArray("whitelist")),
    )
    private fun strings(array: JSONArray?): Set<String> = if (array == null) emptySet() else
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
}
