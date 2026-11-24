package io.github.xblocker.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One "download all" run: enough to show success / rate-limited / failure later, even after
 *  the files themselves are deleted. */
internal data class DownloadBatch(
    val id: String,
    val folder: String,
    val name: String?,
    val handle: String?,
    val avatar: String?,
    val total: Int,
    val time: Long,
    val success: Int,
    val limited: Int,
    val failed: Int,
)

internal class MediaDownloadHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("media_download_history", Context.MODE_PRIVATE)

    fun start(folder: String, name: String?, handle: String?, avatar: String?): String = synchronized(LOCK) {
        val id = UUID.randomUUID().toString()
        val list = read()
        list.put(JSONObject().put("id", id).put("folder", folder).put("handle", handle).put("name", name)
            .put("avatar", avatar).put("total", 0).put("time", System.currentTimeMillis())
            .put("success", 0).put("limited", 0).put("failed", 0))
        while (list.length() > 200) list.remove(0)
        write(list)
        id
    }

    fun setTotal(id: String, total: Int) = synchronized(LOCK) { update(id) { it.put("total", total) } }

    fun remove(id: String) = synchronized(LOCK) {
        val list = read()
        for (index in list.length() - 1 downTo 0) {
            if (list.optJSONObject(index)?.optString("id") == id) list.remove(index)
        }
        write(list)
    }

    /** Records already-saved media before it is deleted, so the history survives the files. */
    fun seed(folder: String, name: String?, handle: String?, avatar: String?, success: Int,
             time: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        if (folder.isEmpty() || success <= 0) return@synchronized
        val list = read()
        list.put(JSONObject().put("id", UUID.randomUUID().toString()).put("folder", folder).put("handle", handle)
            .put("name", name).put("avatar", avatar).put("total", success).put("time", time)
            .put("success", success).put("limited", 0).put("failed", 0))
        while (list.length() > 200) list.remove(0)
        write(list)
    }

    /** Terminal state of one media item; rate limits are reported separately from real failures. */
    fun finish(id: String?, success: Boolean, rateLimited: Boolean = false) = synchronized(LOCK) {
        if (id.isNullOrEmpty()) return@synchronized
        update(id) { entry ->
            val key = if (success) "success" else if (rateLimited) "limited" else "failed"
            entry.put(key, entry.optInt(key) + 1)
        }
    }

    fun recent(): List<DownloadBatch> = synchronized(LOCK) {
        read().let { list ->
            (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.toBatch() }
                .sortedByDescending { it.time }
        }
    }

    private fun JSONObject.toBatch() = DownloadBatch(
        id = optString("id"),
        folder = optString("folder"),
        name = optString("name").takeIf { it.isNotEmpty() },
        handle = optString("handle").takeIf { it.isNotEmpty() },
        avatar = optString("avatar").takeIf { it.isNotEmpty() },
        total = optInt("total"),
        time = optLong("time"),
        success = optInt("success"),
        limited = optInt("limited"),
        failed = optInt("failed"),
    )

    private fun read(): JSONArray = runCatching {
        JSONArray(prefs.getString("batches", "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun write(list: JSONArray) {
        prefs.edit().putString("batches", list.toString()).apply()
    }

    private companion object { val LOCK = Any() }

    private fun update(id: String, block: (JSONObject) -> Unit) {
        val list = read()
        for (index in 0 until list.length()) {
            val entry = list.optJSONObject(index) ?: continue
            if (entry.optString("id") == id) {
                block(entry)
                write(list)
                return
            }
        }
    }
}