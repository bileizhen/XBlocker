package io.github.xblocker.download

import android.content.Context
import org.json.JSONArray

/**
 * Remembers media already saved under each Download/X folder so repeat "download all" runs skip
 * them. Keys are tweetId_mediaId, matching the file names the worker writes.
 */
internal class MediaDownloadLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("media_downloads", Context.MODE_PRIVATE)

    fun key(tweetId: String, mediaId: String) = "${tweetId}_$mediaId"

    fun keys(folder: String): MutableSet<String> = synchronized(LOCK) { read(folder).toMutableSet() }

    fun record(folder: String, keys: Collection<String>) = synchronized(LOCK) {
        if (folder.isEmpty() || keys.isEmpty()) return@synchronized
        val all = read(folder).toMutableSet()
        all += keys
        val trimmed = if (all.size > 4000) all.toList().takeLast(4000) else all.toList()
        prefs.edit().putString(folder, JSONArray(trimmed).toString()).apply()
    }

    /** A deleted folder must be downloadable again from scratch. */
    fun clear(folder: String) = synchronized(LOCK) {
        if (folder.isNotEmpty()) prefs.edit().remove(folder).apply()
    }

    /** A failed download should stay retryable on the next run. */
    fun forget(folder: String, key: String) = synchronized(LOCK) {
        if (folder.isEmpty()) return@synchronized
        val all = read(folder).toMutableSet()
        if (all.remove(key)) prefs.edit().putString(folder, JSONArray(all.toList()).toString()).apply()
    }

    private companion object { val LOCK = Any() }

    private fun read(folder: String): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(folder, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotEmpty() }
        }.toSet()
    }.getOrDefault(emptySet())
}