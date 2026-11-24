package io.github.xblocker.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileMediaTimelineTest {
    private fun image(id: String, url: String, allow: Boolean = true) = """
        {"media_id":"$id","media_results":{"result":{"__typename":"ApiMedia","media_id":"$id",
        "media_info":{"__typename":"ApiImage","original_img_url":"$url"},
        "allow_download_status":{"allow_download":$allow}}}}
    """.trimIndent()

    private fun video(id: String, variants: String) = """
        {"media_id":"$id","media_results":{"result":{"__typename":"ApiMedia","media_id":"$id",
        "media_info":{"__typename":"ApiVideo","variants":$variants}}}}
    """.trimIndent()

    private fun tweet(id: String, vararg media: String) =
        """{"__typename":"Tweet","rest_id":"$id","media_entities":[${media.joinToString(",")}]}"""

    private fun entry(id: String, tweet: String) = """
        {"entry_id":"tweet-$id","content":{"__typename":"TimelineTimelineItem","itemContent":
        {"__typename":"TimelineTweet","tweet_results":{"result":$tweet}}}}
    """.trimIndent()

    private fun cursor(value: String) =
        """{"entry_id":"cursor-bottom-$value","content":{"__typename":"TimelineTimelineCursor","cursor_type":"Bottom","value":"$value"}}"""

    private fun page(vararg entries: String, terminate: Boolean = false): String {
        val tail = if (terminate) """,{"__typename":"TimelineTerminateTimeline","direction":"Bottom"}""" else ""
        return """{"data":{"user_result":{"result":{"__typename":"User","rest_id":"9","timeline_response":
            {"timeline":{"id":"x","instructions":[{"__typename":"TimelineAddEntries",
            "entries":[${entries.joinToString(",")}]}$tail]}}}}}}"""
    }

    private val mp4 = """[
        {"content_type":"video/mp4","bit_rate":320000,"url":"https://video.twimg.com/a/320.mp4?tag=1"},
        {"content_type":"video/mp4","bit_rate":1280000,"url":"https://video.twimg.com/a/1280.mp4?tag=1"},
        {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/a/playlist.m3u8"}
    ]"""

    @Test fun `parses image video and best mp4 with bottom cursor`() {
        val parsed = ProfileMediaParser.parse(page(
            entry("100", tweet("100", image("200", "https://pbs.twimg.com/media/abc?format=jpg&name=small"),
                video("201", mp4))),
            cursor("CUR1"),
        ))
        assertTrue(parsed.recognized)
        assertEquals(1, parsed.tweetCount)
        assertEquals("CUR1", parsed.nextCursor)
        assertEquals(2, parsed.items.size)
        val photo = parsed.items.first { it.mediaId == "200" }
        assertEquals("image", photo.type)
        assertEquals("https://pbs.twimg.com/media/abc?format=jpg&name=orig", photo.url)
        assertEquals("100", photo.tweetId)
        val clip = parsed.items.first { it.mediaId == "201" }
        assertEquals("video", clip.type)
        assertEquals("https://video.twimg.com/a/1280.mp4?tag=1", clip.url)
    }

    @Test fun `visibility wrapper modules and termination are handled`() {
        val wrapped = """
            {"entry_id":"tweet-300","content":{"__typename":"TimelineTimelineItem","itemContent":
            {"__typename":"TimelineTweet","tweet_results":{"result":{"__typename":"TweetWithVisibilityResults",
            "tweet":${tweet("300", image("301", "https://pbs.twimg.com/media/x?format=png"))}}}}}}
        """.trimIndent()
        val module = """
            {"entry_id":"module-1","content":{"__typename":"TimelineTimelineModule","items":[
            {"entry_id":"module-item-1","item":{"itemContent":{"__typename":"TimelineTweet",
            "tweet_results":{"result":${tweet("400", video("401", mp4))}}}}}]}}
        """.trimIndent()
        val parsed = ProfileMediaParser.parse(page(wrapped, module, terminate = true))
        assertTrue(parsed.terminated)
        assertNull(parsed.nextCursor)
        assertEquals(setOf("301", "401"), parsed.items.map { it.mediaId }.toSet())
    }

    @Test fun `unavailable and disallowed media are skipped or flagged`() {
        val restricted = """
            {"entry_id":"tweet-500","content":{"__typename":"TimelineTimelineItem","itemContent":
            {"__typename":"TimelineTweet","tweet_results":{"result":{"__typename":"Tweet","rest_id":"500",
            "media_entities":[
              {"media_id":"501","media_results":{"result":{"media_id":"501",
                "media_info":{"__typename":"ApiImage","original_img_url":"https://pbs.twimg.com/media/r?format=jpg"},
                "media_availability_v2":{"status":"CopyrightViolation"}}}},
              {"media_id":"502","media_results":{"result":{"media_id":"502",
                "media_info":{"__typename":"ApiImage","original_img_url":"https://pbs.twimg.com/media/a?format=jpg"},
                "allow_download_status":{"allow_download":false}}}}
            ]}}}}}
        """.trimIndent()
        val parsed = ProfileMediaParser.parse(page(restricted))
        assertEquals(1, parsed.items.size)
        assertEquals("502", parsed.items.single().mediaId)
        assertFalse(parsed.items.single().allowDownload)
        assertEquals(1, parsed.skipped)
    }

    @Test fun `malformed or unrelated json is not recognized`() {
        assertFalse(ProfileMediaParser.parse("not json").recognized)
        assertFalse(ProfileMediaParser.parse("""{"data":{"unexpected":{}}}""").recognized)
        assertFalse(ProfileMediaParser.parse("""{"errors":[{"message":"x"}]}""").recognized)
    }

    @Test fun `run deduplicates and enforces caps`() {
        val first = ProfileMediaParser.parse(page(entry("1", tweet("1", image("2", "https://pbs.twimg.com/media/a?format=jpg"))), cursor("C1")))
        val second = ProfileMediaParser.parse(page(entry("1", tweet("1", image("2", "https://pbs.twimg.com/media/a?format=jpg"))), cursor("C2")))
        val run = ProfileMediaRun(maxPages = 2, maxMedia = 10)
        assertEquals(1, run.accept(first).size)
        assertTrue(run.accept(second).isEmpty())
        assertEquals(1, run.duplicates)
        assertTrue(run.done)
        assertTrue(run.capped)
    }

    @Test fun `photo normalization rejects foreign or invalid urls`() {
        assertNull(ProfileMediaParser.normalizePhoto("https://evil.test/media/a.jpg"))
        assertNull(ProfileMediaParser.normalizePhoto("http://pbs.twimg.com/media/a.jpg"))
        assertEquals("https://pbs.twimg.com/media/a?format=gif&name=orig",
            ProfileMediaParser.normalizePhoto("https://pbs.twimg.com/media/a.gif?name=small"))
        assertNotNull(ProfileMediaParser.normalizePhoto("https://pbs.twimg.com/media/a?format=webp&name=small"))
    }
}
