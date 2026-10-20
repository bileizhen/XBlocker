package io.github.xblocker.download

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real CDN and scoped-storage test. No download occurs unless mediaDownloadUrl is supplied. */
@RunWith(AndroidJUnit4::class)
class MediaDownloadRuntimeTest {
    @Test fun savesRealCdnMediaToPublicDownloadsThenCleansTestFile() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("mediaDownloadUrl").orEmpty()
        assumeTrue(Build.VERSION.SDK_INT >= 29 && url.isNotBlank())
        val type = args.getString("mediaDownloadType") ?: "image"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = MediaDownload.enqueue(context, url, "runtime_test", "media_test", type)
        assertTrue(result.message, result.queued)
        assertNotNull(result.requestId)
        val deadline = System.currentTimeMillis() + 90_000
        var status: Map<String, Any?>
        while (true) {
            status = MediaDownload.status(context, result.requestId!!)
            if (status["state"] in setOf("SUCCEEDED", "FAILED", "CANCELLED") || System.currentTimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        assertEquals(status.toString(), "SUCCEEDED", status["state"])
        val uri = android.net.Uri.parse(status["uri"] as String)
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH,
                MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Download/X/", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.getLong(2) > 0)
            }
            assertTrue((status["bytes"] as Long) > 0)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
