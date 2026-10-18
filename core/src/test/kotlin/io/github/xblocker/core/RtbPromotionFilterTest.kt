package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * RTB regression coverage and sanitized fixture contributed by TheOneAdonis in
 * https://github.com/bileizhen/XBlocker/pull/1, adapted to the current SSP classification.
 */
class RtbPromotionFilterTest {
    private fun envelope(vararg entries: JSONObject) = JSONObject().put("entries", JSONArray(entries.toList())).toString()
    private fun filter(settings: FilterSettings = FilterSettings()) = TimelineFilter(RuleEngine(settings, "spam"))
    private fun rtb(metadataKey: String? = null) = JSONObject().put("entry_id", "rtb-1").put("content",
        JSONObject().put("content", JSONObject().put("__typename", "TimelineRtbImageAd").apply {
            if (metadataKey != null) put(metadataKey, JSONObject().put("impression_id", "synthetic"))
        }))
    private fun tweet(id: String, text: String) = JSONObject().put("entry_id", "tweet-$id").put("content",
        JSONObject().put("content", JSONObject().put("tweet_results", JSONObject().put("result",
            JSONObject().put("rest_id", id).put("legacy", JSONObject().put("full_text", text)
                .put("in_reply_to_status_id_str", "root"))))))

    @Test fun `nested RTB typename works with raw aliased or absent promotion metadata`() {
        for (key in listOf("rtb_promoted_metadata", "promoted_metadata", null)) {
            val input = envelope(rtb(key), tweet("2", "hello"))
            val out = filter().filter(input)
            assertEquals(listOf("视频广告"), out.events.map { it.reason }, "metadata=$key")
            assertEquals("SSP:TimelineRtbImageAd", out.events.single().rule)
            assertEquals("tweet-2", JSONObject(out.json).getJSONArray("entries").getJSONObject(0).getString("entry_id"))
            assertEquals(input, filter(FilterSettings(blockPromoted = false)).filter(input).json)
            assertEquals(input, filter(FilterSettings(enabled = false)).filter(input).json)
        }
    }

    @Test fun `RTB wrapper never hides the tweet from keyword matching`() {
        val wrapped = tweet("1", "spam")
        wrapped.getJSONObject("content").put("rtb_promoted_metadata", JSONObject())
        val f = filter(FilterSettings(blockPromoted = false))
        val out = f.filter(envelope(wrapped, tweet("2", "hello")))
        assertEquals(listOf("正文"), out.events.map { it.reason })
        assertEquals(2L, f.stats.tweets.get())
        assertEquals(2L, f.stats.replies.get())
    }

    @Test fun `quoted RTB marker cannot promote a clean outer tweet`() {
        val clean = tweet("1", "hello")
        clean.getJSONObject("content").getJSONObject("content").getJSONObject("tweet_results").getJSONObject("result")
            .put("quoted_status_result", rtb("rtb_promoted_metadata"))
        val input = envelope(clean)
        assertEquals(input, filter().filter(input).json)
    }

    @Test fun `RTB module items and spam are removed while clean siblings and cursors survive`() {
        val module = JSONObject().put("entry_id", "module-1").put("content", JSONObject().put("items",
            JSONArray().put(rtb("promoted_metadata")).put(tweet("2", "spam")).put(tweet("3", "hello"))))
        val cursor = JSONObject().put("entry_id", "cursor-bottom").put("content", JSONObject().put("cursor_type", "Bottom"))
        val f = filter()
        val out = f.filter(envelope(module, cursor))
        assertEquals(setOf("视频广告", "正文"), out.events.map { it.reason }.toSet())
        val entries = JSONObject(out.json).getJSONArray("entries")
        assertEquals(2, entries.length())
        assertEquals("tweet-3", entries.getJSONObject(0).getJSONObject("content").getJSONArray("items")
            .getJSONObject(0).getString("entry_id"))
        assertEquals("cursor-bottom", entries.getJSONObject(1).getString("entry_id"))
    }

    @Test fun `RTB bookkeeping fields never turn an ordinary feed into ads`() {
        // These are synthetic boundary cases, not a captured device response. A metadata
        // field alone must not promote a TimelineTweet, even when it contains values.
        for (metadata in listOf(JSONObject.NULL, JSONObject(), JSONObject().put("impression_id", "bookkeeping"))) {
            val posts = (1..60).map { id -> tweet(id.toString(), "hello").apply {
                getJSONObject("content").put("rtb_promoted_metadata", metadata)
                    .getJSONObject("content").put("__typename", "TimelineTweet")
                    .put("rtb_promoted_metadata", metadata)
            } }.toTypedArray()
            val input = envelope(*posts)
            val f = filter()
            val out = f.filter(input)
            assertTrue(input == out.json, "metadata=$metadata, retained=${JSONObject(out.json).getJSONArray("entries").length()}")
            assertTrue(out.events.isEmpty())
            assertEquals(60L, f.stats.tweets.get())
            assertEquals(0L, f.stats.promoted.get())
        }
    }

    @Test fun `standalone RTB metadata without a recognized ad type stays untouched`() {
        val unknown = JSONObject().put("entry_id", "unknown-1").put("content", JSONObject()
            .put("rtb_promoted_metadata", JSONObject().put("impression_id", "bookkeeping")))
        val input = envelope(unknown, tweet("2", "hello"))
        assertEquals(input, filter().filter(input).json)
    }

    @Test fun `RTB types in unrelated auxiliary data do not promote an ordinary tweet`() {
        val clean = tweet("1", "hello")
        clean.getJSONObject("content").put("auxiliary", JSONObject().put("__typename", "TimelineRtbImageAd"))
        val input = envelope(clean)
        assertEquals(input, filter().filter(input).json)
    }

    @Test fun `enabled promotion blocking keeps keyword reasons for RTB metadata on normal tweets`() {
        val spam = tweet("1", "spam")
        spam.getJSONObject("content").put("rtb_promoted_metadata", JSONObject())
        val clean = tweet("2", "hello")
        clean.getJSONObject("content").put("rtb_promoted_metadata", JSONObject())
        val out = filter().filter(envelope(spam, clean))
        assertEquals(listOf("正文"), out.events.map { it.reason })
        assertEquals("tweet-2", JSONObject(out.json).getJSONArray("entries").getJSONObject(0).getString("entry_id"))
    }

    @Test fun `captured X 12-34 conversation module RTB item is removed`() {
        // Sanitized sample from a real X 12.34 conversation timeline: the RTB ad
        // uses the item wrapper inside a module. IDs and tracking payload are redacted.
        val input = javaClass.classLoader!!.getResourceAsStream("rtb-image-ad-x12.34.json")!!
            .use { it.readBytes().decodeToString() }
        val out = filter().filter(input)
        assertTrue(out.recognized)
        assertEquals(1, out.events.size)
        assertEquals("SSP:TimelineRtbImageAd", out.events.single().rule)
        val expected = JSONObject(input)
        expected.getJSONObject("data").getJSONObject("timelineResponse")
            .getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")
            .getJSONObject(0).getJSONObject("content").getJSONArray("items").remove(0)
        assertTrue(expected.similar(JSONObject(out.json)), "Only the RTB module item should change")
        for (settings in listOf(FilterSettings(blockPromoted = false), FilterSettings(enabled = false))) {
            assertEquals(input, filter(settings).filter(input).json)
        }
    }
}
