package io.github.xblocker.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class MediaDownloadPolicyTest {
    @Test fun acceptsOnlyDirectHttpsCdnMedia() {
        val image = MediaDownloadPolicy.Kind.IMAGE
        val video = MediaDownloadPolicy.Kind.VIDEO
        assertEquals("pbs.twimg.com", MediaDownloadPolicy.validateUrl("https://pbs.twimg.com/media/Abc?format=jpg&name=orig", image).host)
        assertEquals("video.twimg.com", MediaDownloadPolicy.validateUrl("https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/720x1280/a.mp4?tag=12", video).host)
        listOf("http://pbs.twimg.com/media/a.jpg", "https://pbs.twimg.com.evil.test/media/a.jpg",
            "https://user@pbs.twimg.com/media/a.jpg", "https://pbs.twimg.com:8443/media/a.jpg",
            "https://pbs.twimg.com/profile_images/a.jpg", "https://pbs.twimg.com/media/a.jpg#x",
            "https://127.0.0.1/media/a.jpg", "https://pbs.twimg.com/media/a.jpg\r\nX: value").forEach {
            assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.validateUrl(it, image) }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.validateUrl("https://video.twimg.com/path/playlist.m3u8", video) }
        assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.validateUrl("https://pbs.twimg.com/media/a.mp4", video) }
    }

    @Test fun validatesEveryRedirectTarget() {
        val original = URI("https://video.twimg.com/path/a.mp4")
        val kind = MediaDownloadPolicy.Kind.VIDEO
        assertEquals("https://video.twimg.com/path/b.mp4", MediaDownloadPolicy.validateUrl(original.resolve("b.mp4").toString(), kind).toString())
        listOf("https://evil.test/a.mp4", "http://video.twimg.com/a.mp4", "//127.0.0.1/a.mp4").forEach {
            assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.validateUrl(original.resolve(it).toString(), kind) }
        }
    }

    @Test fun filenameIdentifiersCannotEscapeDestination() {
        assertEquals("123456_789", MediaDownloadPolicy.validateId("123456_789"))
        listOf("", "../other", "a/b", "x.jpg", "a\\b", "照片", "a".repeat(81)).forEach {
            assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.validateId(it) }
        }
    }

    @Test fun validatesMimeAndActualSignature() {
        assertEquals("image/jpeg", MediaDownloadPolicy.contentType("image/jpeg; charset=binary", MediaDownloadPolicy.Kind.IMAGE))
        assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.contentType("text/html", MediaDownloadPolicy.Kind.IMAGE) }
        assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.contentType("image/jpeg", MediaDownloadPolicy.Kind.VIDEO) }
        MediaDownloadPolicy.verifyHeader(byteArrayOf(-1, -40, -1), 3, "image/jpeg")
        MediaDownloadPolicy.verifyHeader(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109), 12, "video/mp4")
        MediaDownloadPolicy.verifyHeader("RIFF1234WEBP".toByteArray(), 12, "image/webp")
        assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.verifyHeader("<html>error".toByteArray(), 11, "image/jpeg") }
        assertThrows(IllegalArgumentException::class.java) { MediaDownloadPolicy.verifyHeader(byteArrayOf(-1, -40), 2, "image/jpeg") }
    }

    @Test fun uniqueWorkKeyIncludesMediaAndTweet() {
        val url = "https://pbs.twimg.com/media/a.jpg"
        assertEquals(MediaDownloadPolicy.key(url, "1", "2"), MediaDownloadPolicy.key(url, "1", "2"))
        assertNotEquals(MediaDownloadPolicy.key(url, "1", "2"), MediaDownloadPolicy.key(url, "1", "3"))
        assertEquals(64, MediaDownloadPolicy.key(url, "1", "2").length)
    }
}
