package io.github.xblocker.core

import org.json.JSONObject

data class AppRelease(val version: String, val notes: String, val url: String)

object ReleaseParser {
    private fun version(value: String): List<Long>? {
        val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
    }

    fun newerRelease(json: String, installed: String): AppRelease? {
        val current = version(installed) ?: return null
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        val latest = version(tag) ?: return null
        val difference = latest.zip(current).firstOrNull { (a, b) -> a != b } ?: return null
        if (difference.first < difference.second) return null
        val url = "https://github.com/bileizhen/XBlocker/releases/tag/$tag"
        if (release.optString("html_url") != url) return null
        // A source-only release is not an installable app update.
        val assets = release.optJSONArray("assets") ?: return null
        if ((0 until assets.length()).none { i ->
            val asset = assets.optJSONObject(i)
            val name = asset?.optString("name").orEmpty()
            name.endsWith(".apk", ignoreCase = true) &&
                asset?.optString("browser_download_url")?.startsWith("https://github.com/bileizhen/XBlocker/releases/download/$tag/") == true
        }) return null
        return AppRelease(tag.removePrefix("v"), release.optString("body").take(6000), url)
    }
}
