package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Minimized from X 12.25.0 device responses; no captured text, accounts or IDs. */
class X1225RepostTest {
    private fun tweet(id: String = "1") = JSONObject("""{
        "__typename":"Tweet","rest_id":"$id","details":{"full_text":"ordinary post"},"legacy":{}
    }""")
    private fun entry(tweet: JSONObject) = JSONObject().put("entry_id", "tweet-${tweet.optString("rest_id")}")
        .put("content", JSONObject().put("__typename", "TimelineTimelineItem")
            .put("content", JSONObject().put("__typename", "TimelineTweet")
                .put("tweet_results", JSONObject().put("result", tweet))))
    private fun envelope(vararg entries: JSONObject) = JSONObject().put("data", JSONObject().put("timeline_response",
        JSONObject().put("timeline", JSONObject().put("instructions", JSONArray().put(JSONObject()
            .put("type", "TimelineAddEntries").put("entries", JSONArray(entries.toList()))))))).toString()
    private fun filter(enabled: Boolean = true) = TimelineFilter(RuleEngine(FilterSettings(blockReposts = enabled), ""))

    @Test fun `1225 repostedStatusResults removes repost and preserves ordinary post and cursor`() {
        val repost = tweet()
        repost.getJSONObject("legacy").put("repostedStatusResults", JSONObject().put("rest_id", "2").put("result", tweet("2")))
        val clean = entry(tweet("3"))
        val cursor = JSONObject("""{"entry_id":"cursor-bottom","content":{"cursor_type":"Bottom","value":"opaque"}}""")
        val input = envelope(entry(repost), clean, cursor)
        assertEquals(input, filter(false).filter(input).json)
        val output = filter().filter(input)
        assertEquals(1, output.events.size)
        assertEquals("repostedStatusResults", output.events.single().rule)
        assertEquals("时间线转帖", output.events.single().reason)
        val kept = JSONObject(output.json).getJSONObject("data").getJSONObject("timeline_response")
            .getJSONObject("timeline").getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")
        assertEquals(listOf(clean.toString(), cursor.toString()), (0 until kept.length()).map { kept.getJSONObject(it).toString() })
    }

    @Test fun `1225 quote of a repost remains visible and null references fail open`() {
        val repost = tweet("2")
        repost.getJSONObject("legacy").put("repostedStatusResults", JSONObject().put("result", tweet("3")))
        val quote = tweet().put("quotedPostResults", JSONObject().put("result", repost))
            .put("quoted_tweet_results", JSONObject().put("result", repost))
        val input = envelope(entry(quote))
        assertEquals(input, filter().filter(input).json)
        for (reference in listOf(JSONObject.NULL, JSONObject(), JSONObject().put("result", JSONObject.NULL), JSONObject().put("result", JSONObject()))) {
            val clean = tweet()
            clean.getJSONObject("legacy").put("repostedStatusResults", reference)
            val plain = envelope(entry(clean))
            assertEquals(plain, filter().filter(plain).json)
        }
    }

    @Test fun `1225 repost inside visibility wrapper is removed`() {
        val repost = tweet()
        repost.getJSONObject("legacy").put("repostedStatusResults", JSONObject().put("result", tweet("2")))
        val wrapped = JSONObject().put("__typename", "TweetWithVisibilityResults").put("tweet", repost)
        assertEquals(1, filter().filter(envelope(entry(wrapped))).events.size)
    }

}
