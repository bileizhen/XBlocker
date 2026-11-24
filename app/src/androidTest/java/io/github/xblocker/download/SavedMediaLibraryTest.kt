package io.github.xblocker.download

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.data.SavedMediaLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds an app-owned download and proves the Media tab groups it under the per-user folder. */
@RunWith(AndroidJUnit4::class)
class SavedMediaLibraryTest {
    @Test fun ownDownloadsAreGroupedByUserFolder() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = "987654321(@xbtest_${System.currentTimeMillis() % 100000})"
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "xblocker_media_test.jpg")
            put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, MediaDownloadPolicy.relativePath(folder))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: error("Could not create test download")
        try {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                resolver.openOutputStream(uri, "w")!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { bitmap.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val group = SavedMediaLibrary.groups(context).firstOrNull { it.folder == folder }
            assertNotNull("Missing group for $folder", group)
            assertEquals(1, group!!.count)
            assertEquals("987654321", group.userId)
            assertEquals(folder.substringAfter("(@").substringBefore(')'), group.handle)
            assertTrue(group.items.any { it.uri == uri })
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
