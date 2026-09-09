package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject

data class AppRelease(
    val version: String,
    val notes: String,
    val url: String,
    val downloadUrl: String,
    val assetName: String,
)

/**
 * x.y.z with an optional -alpha.N / -beta.N / -rc.N suffix. A pre-release sorts below the
 * same x.y.z final, suffixes of one kind compare by N (semver subset used by our tags).
 */
private data class Version(val numbers: List<Long>, val suffixKind: Int, val suffixNumber: Long) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (suffixKind != other.suffixKind) return suffixKind.compareTo(other.suffixKind)
        return suffixNumber.compareTo(other.suffixNumber)
    }

    companion object {
        val FINAL = 3
        val RC = 2
        val BETA = 1
        val ALPHA = 0

        fun parse(value: String): Version? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.(0|[1-9][0-9]*))?").matchEntire(value) ?: return null
            val numbers = match.groupValues.drop(1).take(3).map { it.toLongOrNull() ?: return null }
            val kind = when (match.groupValues[4]) {
                "alpha" -> ALPHA
                "beta" -> BETA
                "rc" -> RC
                else -> FINAL
            }
            val suffix = match.groupValues[5].toLongOrNull() ?: 0L
            return Version(numbers, kind, suffix)
        }
    }
}

object ReleaseParser {
    fun newerRelease(json: String, installed: String): AppRelease? {
        val current = Version.parse(installed) ?: return null
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        return eligible(release, current, allowPrerelease = false)
    }

    /** Pre-release channel: newest non-draft release from the list, stable or pre-release. */
    fun newestRelease(json: String, installed: String): AppRelease? {
        val current = Version.parse(installed) ?: return null
        val releases = JSONArray(json)
        var best: AppRelease? = null
        var bestVersion: Version? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val candidate = eligible(release, current, allowPrerelease = true) ?: continue
            val version = Version.parse(release.optString("tag_name")) ?: continue
            if (bestVersion == null || version > bestVersion) {
                bestVersion = version
                best = candidate
            }
        }
        return best
    }

    /** Returns the release when it parses, is strictly newer than installed and ships an APK. */
    private fun eligible(release: JSONObject, installed: Version, allowPrerelease: Boolean): AppRelease? {
        val tag = release.optString("tag_name")
        val candidate = Version.parse(tag) ?: return null
        if (candidate <= installed) return null
        // Defensive: the stable channel also rejects suffixed tags that were published
        // without GitHub's pre-release flag.
        if (!allowPrerelease && candidate.suffixKind != Version.Companion.FINAL) return null
        val url = "https://github.com/bileizhen/XBlocker/releases/tag/$tag"
        if (release.optString("html_url") != url) return null
        // A source-only release is not an installable app update.
        val assets = release.optJSONArray("assets") ?: return null
        val asset = (0 until assets.length()).mapNotNull { i ->
            val asset = assets.optJSONObject(i)
            val name = asset?.optString("name").orEmpty()
            if (name.endsWith(".apk", ignoreCase = true) &&
                asset?.optString("browser_download_url")?.startsWith("https://github.com/bileizhen/XBlocker/releases/download/$tag/") == true
            ) asset else null
        }.firstOrNull() ?: return null
        return AppRelease(
            version = tag.removePrefix("v"),
            // Bilingual release notes roughly double the body length; keep a generous cap.
            notes = release.optString("body").take(12_000),
            url = url,
            downloadUrl = asset.optString("browser_download_url"),
            assetName = asset.optString("name"),
        )
    }
}
