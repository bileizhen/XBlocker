package io.github.xblocker.data

import android.content.Context
import io.github.xblocker.download.MediaDownloadPolicy
import org.json.JSONObject

internal data class MediaUser(val id: String, val handle: String, val name: String?, val avatar: String?)

/**
 * X's profile cards carry 48px `_normal` avatars. pbs.twimg.com accepts a size suffix, so ask for
 * the 400px variant for the Media tab instead of stretching the thumbnail.
 */
internal fun sharpAvatarUrl(url: String?): String? {
    val value = url?.trim()?.takeIf {
        it.startsWith("https://pbs.twimg.com/profile_images/") && it.none { c -> c.isWhitespace() }
    } ?: return null
    return value.replace(
        Regex("_(?:normal|mini|bigger|reasonably_small)(\\.(?:jpg|jpeg|png|webp|gif))$", RegexOption.IGNORE_CASE),
        "_400x400$1")
}

/**
 * Metadata for Download/X/<userId>(@handle) folders. Written by the host when a batch download
 * starts; never trusted at read time, so every field is re-validated here.
 */
internal class MediaUserStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("media_users", Context.MODE_PRIVATE)

    fun save(folder: String, id: String, handle: String, name: String?, avatar: String?): Boolean = synchronized(LOCK) { runCatching {
        val safeFolder = MediaDownloadPolicy.folder(folder)
        if (safeFolder.isEmpty()) return false
        val safeId = id.trim().takeIf { it.length in 1..32 && it.all { c -> c.isDigit() } } ?: return false
        val safeHandle = handle.trim().removePrefix("@").takeIf {
            it.length in 1..15 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' }
        } ?: return false
        val safeName = name?.trim()?.take(64)?.takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() } }
        val safeAvatar = sharpAvatarUrl(avatar)?.take(512)
        val users = read()
        users.put(safeFolder, JSONObject().put("id", safeId).put("handle", safeHandle).apply {
            if (safeName != null) put("name", safeName)
            if (safeAvatar != null) put("avatar", safeAvatar)
        })
        while (users.length() > 500) users.remove(users.keys().next())
        prefs.edit().putString("users", users.toString()).commit()
    }.getOrDefault(false) }

    /** Drops the metadata of a folder whose media were deleted. */
    fun remove(folder: String) = synchronized(LOCK) {
        val users = read()
        if (users.has(folder)) {
            users.remove(folder)
            prefs.edit().putString("users", users.toString()).commit()
        }
    }

    fun all(): Map<String, MediaUser> = synchronized(LOCK) {
        val users = read()
        val result = linkedMapOf<String, MediaUser>()
        for (folder in users.keys()) {
            val entry = users.optJSONObject(folder) ?: continue
            val id = entry.optString("id").takeIf { it.length in 1..32 && it.all { c -> c.isDigit() } } ?: continue
            val handle = entry.optString("handle").takeIf {
                it.length in 1..15 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' }
            } ?: continue
            val name = entry.optString("name").take(64).takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() } }
            val avatar = sharpAvatarUrl(entry.optString("avatar"))?.take(512)
            result[folder] = MediaUser(id, handle, name, avatar)
        }
        result
    }

    private companion object { val LOCK = Any() }

    private fun read(): JSONObject = runCatching {
        JSONObject(prefs.getString("users", "{}") ?: "{}")
    }.getOrDefault(JSONObject())
}
