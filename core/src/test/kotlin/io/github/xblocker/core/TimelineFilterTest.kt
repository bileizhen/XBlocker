package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

class TimelineFilterTest {
    private fun tweet(id: String, text: String, reply: Boolean = true): JSONObject = JSONObject()
        .put("entryId", "tweet-$id").put("sortIndex", id)
        .put("content", JSONObject().put("itemContent", JSONObject().put("tweet_results", JSONObject().put("result", JSONObject()
            .put("__typename", "Tweet").put("rest_id", id)
            .put("legacy", JSONObject().put("full_text", text).put("in_reply_to_status_id_str", if (reply) "100" else JSONObject.NULL))
            .put("core", JSONObject().put("user_results", JSONObject().put("result", JSONObject().put("core", JSONObject().put("screen_name", "alice").put("name", "Alice")))))))))
    private fun result(entry: JSONObject) = entry.getJSONObject("content").getJSONObject("itemContent").getJSONObject("tweet_results").getJSONObject("result")
    private fun envelope(vararg entries: JSONObject) = JSONObject().put("data", JSONObject().put("timeline", JSONObject().put("instructions", JSONArray().put(JSONObject().put("entries", JSONArray(entries.toList())))))).toString()
    private fun entries(json: String): JSONArray = JSONObject(json).getJSONObject("data").getJSONObject("timeline").getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")
    private fun filter(settings: FilterSettings = FilterSettings()) = TimelineFilter(RuleEngine(settings, "spam"))

    @Test fun `removes spam preserves normal tweets and pagination`() {
        val cursor = JSONObject().put("entryId", "cursor-bottom-1").put("content", JSONObject().put("cursorType", "Bottom").put("value", "opaque"))
        val result = filter().filter(envelope(tweet("1", "spam"), tweet("2", "hello"), cursor))
        assertTrue(result.recognized); assertEquals(1, result.events.size)
        assertEquals(listOf("tweet-2", "cursor-bottom-1"), (0 until entries(result.json).length()).map { entries(result.json).getJSONObject(it).getString("entryId") })
        assertEquals("opaque", entries(result.json).getJSONObject(1).getJSONObject("content").getString("value"))
    }
    @Test fun `quoted spam does not remove clean parent`() {
        val parent = tweet("1", "normal")
        result(parent).put("quoted_status_result", JSONObject().put("result", result(tweet("2", "spam"))))
        val input = envelope(parent)
        assertEquals(input, filter().filter(input).json)
    }
    @Test fun `reply-only filter preserves original post`() {
        assertEquals(0, filter().filter(envelope(tweet("1", "spam", false))).events.size)
    }
    @Test fun `filters nested conversation modules without deleting clean siblings`() {
        val module = JSONObject().put("entryId", "conversation-1").put("content", JSONObject().put("items", JSONArray().put(
            JSONObject().put("entryId", "tweet-1").put("item", tweet("1", "spam").getJSONObject("content"))).put(
            JSONObject().put("entryId", "tweet-2").put("item", tweet("2", "normal").getJSONObject("content")))))
        val output = filter().filter(envelope(module))
        assertEquals(1, output.events.size)
        assertEquals(1, entries(output.json).getJSONObject(0).getJSONObject("content").getJSONArray("items").length())
    }
    @Test fun `empty conversation module is removed`() {
        val module = JSONObject().put("entryId", "conversation-1").put("content", JSONObject().put("items", JSONArray().put(
            JSONObject().put("entryId", "tweet-1").put("item", tweet("1", "spam").getJSONObject("content")))))
        assertEquals(0, entries(filter().filter(envelope(module)).json).length())
    }
    @Test fun `promotion toggle independent of scope and master switch wins`() {
        val ad = tweet("1", "normal", false)
        ad.getJSONObject("content").getJSONObject("itemContent").put("promotedMetadata", JSONObject().put("advertiser_id", "42"))
        val input = envelope(ad)
        assertEquals("推广广告", filter().filter(input).events.single().reason)
        assertEquals(input, filter(FilterSettings(blockPromoted = false)).filter(input).json)
        assertEquals(input, filter(FilterSettings(enabled = false)).filter(input).json)
    }
    @Test fun `long note tweet and visibility wrapper are parsed`() {
        val entry = tweet("1", "truncated")
        val tweet = result(entry).put("note_tweet", JSONObject().put("note_tweet_results", JSONObject().put("result", JSONObject().put("text", "long spam"))))
        entry.getJSONObject("content").getJSONObject("itemContent").getJSONObject("tweet_results").put("result", JSONObject().put("__typename", "TweetWithVisibilityResults").put("tweet", tweet))
        assertEquals(1, filter().filter(envelope(entry)).events.size)
    }
    @Test fun `malformed and unrelated JSON remains identical`() {
        listOf("{bad", "{\"entries\":[", "{\"dm\":{\"full_text\":\"spam\"}}", "[1,2]").forEach { assertEquals(it, filter().filter(it).json) }
    }
    @Test fun `unknown entries and missing tweet fields fail open`() {
        val input = envelope(JSONObject().put("entryId", "unknown-1").put("content", JSONObject().put("full_text", "spam")))
        assertEquals(input, filter().filter(input).json)
    }
    @Test fun `module additions are filtered`() {
        val input = JSONObject().put("moduleItems", JSONArray().put(JSONObject().put("entryId", "tweet-1").put("item", tweet("1", "spam").getJSONObject("content")))).toString()
        assertEquals(0, JSONObject(filter().filter(input).json).getJSONArray("moduleItems").length())
    }
}
