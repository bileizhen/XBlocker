package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Synthetic content using field paths observed on X 12.16.3-release.0. */
class X1216TimelineTest {
    private fun tweet(id: String, text: String): JSONObject = JSONObject("""{
        "entry_id":"tweet-$id", "sort_index":"$id", "content":{
          "__typename":"TimelineTimelineItem", "content":{
            "__typename":"TimelineTweet", "tweet_results":{"rest_id":"$id", "result":{
              "__typename":"Tweet", "rest_id":"$id", "legacy":{},
              "details":{"full_text":"$text"},
              "core":{"user_results":{"result":{"core":{"name":"Alice","screen_name":"alice"}}}}
            }}
          }
        }
    }""")
    private fun result(entry: JSONObject) = entry.getJSONObject("content").getJSONObject("content")
        .getJSONObject("tweet_results").getJSONObject("result")
    private fun envelope(vararg items: JSONObject) = JSONObject().put("data", JSONObject().put("timeline",
        JSONObject().put("instructions", JSONArray().put(JSONObject().put("entries", JSONArray(items.toList())))))).toString()
    private fun entries(json: String) = JSONObject(json).getJSONObject("data").getJSONObject("timeline")
        .getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")
    private fun filter(settings: FilterSettings = FilterSettings()) = TimelineFilter(RuleEngine(settings, "spam"))

    @Test fun `recognizes snake case entries after an unknown first item`() {
        val input = envelope(JSONObject().put("unknown", true), tweet("1", "hello"))
        val f = filter()
        assertTrue(f.filter(input).recognized)
        assertEquals(input, f.filter(input).json)
        assertEquals(2, f.stats.texts.get())
    }

    @Test fun `details text is filtered with an empty legacy object`() {
        val f = filter(FilterSettings(onlyReplies = false))
        val output = f.filter(envelope(tweet("1", "spam"), tweet("2", "hello")))
        assertEquals("1", output.events.single().id)
        assertEquals("alice", output.events.single().handle)
        assertEquals("tweet-2", entries(output.json).getJSONObject(0).getString("entry_id"))
    }

    @Test fun `nested snake case promotion is removed independently of reply scope`() {
        val ad = tweet("1", "hello")
        ad.getJSONObject("content").getJSONObject("content").put("promoted_metadata", JSONObject())
        val cursor = JSONObject("""{"entry_id":"cursor-bottom","content":{"cursor_type":"Bottom","value":"opaque"}}""")
        val input = envelope(ad, cursor)
        val output = filter().filter(input)
        assertEquals("推广广告", output.events.single().reason)
        assertEquals("opaque", entries(output.json).getJSONObject(0).getJSONObject("content").getString("value"))
        assertEquals(input, filter(FilterSettings(blockPromoted = false)).filter(input).json)
    }

    @Test fun `clean parent is preserved when quoted details contain spam`() {
        val parent = tweet("1", "hello")
        result(parent).put("quoted_status_result", JSONObject().put("result", result(tweet("2", "spam"))))
        val input = envelope(parent)
        assertEquals(input, filter(FilterSettings(onlyReplies = false)).filter(input).json)
    }

    @Test fun `reply reference filters only spam children and retains thread root`() {
        val spam = tweet("2", "spam")
        result(spam).put("reply_to_results", JSONObject().put("rest_id", "1"))
        val clean = tweet("3", "hello")
        result(clean).put("reply_to_results", JSONObject().put("rest_id", "1"))
        val module = JSONObject().put("entry_id", "conversation-1").put("content", JSONObject().put("items",
            JSONArray(listOf(spam, clean).map { JSONObject().put("entry_id", it.getString("entry_id")).put("item", it.getJSONObject("content")) })))
        val f = filter()
        val output = f.filter(envelope(tweet("1", "spam"), module))
        assertEquals("2", output.events.single().id)
        assertEquals(2, f.stats.replies.get())
        assertEquals(2, entries(output.json).length())
        assertEquals("tweet-3", entries(output.json).getJSONObject(1).getJSONObject("content")
            .getJSONArray("items").getJSONObject(0).getString("entry_id"))
    }

    @Test fun `empty or null reply reference never marks a root as a reply`() {
        for (reference in listOf(JSONObject.NULL, JSONObject(), JSONObject().put("rest_id", JSONObject.NULL), JSONObject().put("rest_id", "0"))) {
            val root = tweet("1", "spam")
            result(root).put("reply_to_results", reference)
            val input = envelope(root)
            assertEquals(input, filter().filter(input).json)
        }
    }
}
