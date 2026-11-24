package io.github.xblocker.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

internal data class SavedMediaItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val added: Long,
) {
    val video get() = mime.startsWith("video/")
}

internal data class SavedMediaGroup(val folder: String, val items: List<SavedMediaItem>) {
    val count get() = items.size
    val bytes get() = items.sumOf { it.size }
    val latest get() = items.maxOfOrNull { it.added } ?: 0L
    val userId: String? get() = USER_FOLDER.matchEntire(folder)?.groupValues?.getOrNull(1)
    val handle: String? get() = USER_FOLDER.matchEntire(folder)?.groupValues?.getOrNull(2)

    private companion object { val USER_FOLDER = Regex("^(\\d+)\\(@([A-Za-z0-9_]+)\\)$") }
}

/**
 * Read-only view over the manager app's own MediaStore downloads. Files are written by this app
 * through [ModuleProvider], so Android's scoped storage lets us query them without media permissions.
 */
internal object SavedMediaLibrary {
    private const val PREFIX = "Download/X/"

    fun groups(context: Context): List<SavedMediaGroup> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val projection = arrayOf(
            MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED,
        )
        val resolved = runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$PREFIX%"), null,
            )
        }.getOrNull() ?: runCatching {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null, null)
        }.getOrNull() ?: return emptyList()
        val items = mutableListOf<Pair<String, SavedMediaItem>>()
        resolved.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeColumn).orEmpty()
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                val path = cursor.getString(pathColumn).orEmpty()
                if (!path.startsWith(PREFIX)) continue
                val folder = path.removePrefix(PREFIX).trim('/')
                items += folder to SavedMediaItem(
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)),
                    name = cursor.getString(nameColumn).orEmpty(),
                    mime = mime,
                    size = cursor.getLong(sizeColumn),
                    added = cursor.getLong(addedColumn),
                )
            }
        }
        return items.groupBy({ it.first }, { it.second })
            .map { (folder, list) -> SavedMediaGroup(folder, list.sortedByDescending { it.added }) }
            .sortedByDescending { it.latest }
    }

    /** Deletes every saved media item of one folder; returns removed and failed counts. */
    fun deleteFolder(context: Context, folder: String): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT < 29) return 0 to 0
        val group = groups(context).firstOrNull { it.folder == folder } ?: return 0 to 0
        var removed = 0
        var failed = 0
        for (item in group.items) {
            val deleted = runCatching { context.contentResolver.delete(item.uri, null, null) }.getOrDefault(0)
            if (deleted > 0) removed++ else failed++
        }
        return removed to failed
    }
}
