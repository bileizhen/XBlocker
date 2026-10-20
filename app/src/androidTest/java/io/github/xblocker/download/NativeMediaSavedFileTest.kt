package io.github.xblocker.download

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional acceptance check for a file produced by tapping the real X menu; never starts a download. */
class NativeMediaSavedFileTest {
    @Test fun nativeMenuFileIsPublishedAndReadable() {
        val name = InstrumentationRegistry.getArguments().getString("nativeMediaFile")
        assumeTrue(!name.isNullOrEmpty() && Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { cursor ->
            assertTrue("Menu download missing", cursor.moveToFirst())
            assertEquals("Download/X/", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertTrue(cursor.getLong(4) > 12)
            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            if (cursor.getString(1) == "video/mp4") {
                MediaMetadataRetriever().use { media ->
                    media.setDataSource(context, uri)
                    assertTrue(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
                    assertTrue(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() > 0)
                }
            } else {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, bounds)
                    assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
                }
            }
        }
    }
}
