package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

class RepostFilterTest {
    private fun tweet(id: String = "1") = JSONObject("""{
        "__typename":"Tweet","rest_id":"$id","legacy":{"full_text":"hello"},
        "core":{"user_results":{"result":{"core":{"screen_name":"alice"}}}}
    }""")
    private fun item(tweet: JSONObject = tweet()) = JSONObject().put("tweet_results", JSONObject().put("result", tweet))
    private fun entry(item: JSONObject, id: String = "1") = JSONObject().put("entryId", "tweet-$id")
        .put("content", JSONObject().put("itemContent", item))
    private fun envelope(vararg entries: JSONObject) = JSONObject().put("data", JSONObject().put("timeline",
        JSONObject().put("instructions", JSONArray().put(JSONObject().put("entries", JSONArray(entries.toList())))))).toString()
    private fun entries(json: String) = JSONObject(json).getJSONObject("data").getJSONObject("timeline")
        .getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")
    private fun filter(settings: FilterSettings = FilterSettings(blockReposts = true)) = TimelineFilter(RuleEngine(settings, "spam"))
    private fun repostItem() = item().put("socialContext", JSONObject().put("generalContext", JSONObject().put("contextType", "Retweet")))

    @Test fun `removes repost with empty rules while preserving ordinary posts and cursor`() {
        val cursor = JSONObject("""{"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"opaque"}}""")
        val f = filter(FilterSettings(blockReposts = true, cloudEnabled = false, whitelist = setOf("alice")))
        val output = f.filter(envelope(entry(repostItem()), entry(item(tweet("2")), "2"), cursor))
        val event = output.events.single()
        assertEquals(BlockEvent("1", "alice", "时间线转帖", "socialContext.Retweet", "转帖"), event)
        assertEquals("tweet-2", entries(output.json).getJSONObject(0).getString("entryId"))
        assertEquals(cursor.toString(), entries(output.json).getJSONObject(1).toString())
        assertEquals(1, f.stats.blocked.get())
        assertEquals(1, f.stats.toMap()["reposts"])
    }

    @Test fun `reposts stay unchanged by default or with master disabled`() {
        val input = envelope(entry(repostItem()))
        for (settings in listOf(FilterSettings(), FilterSettings(blockReposts = false), FilterSettings(enabled = false, blockReposts = true))) {
            val output = filter(settings).filter(input)
            assertEquals(input, output.json)
            assertTrue(output.events.isEmpty())
        }
    }

    @Test fun `supports camel snake flat and general social contexts on Android content`() {
        for ((socialKey, generalKey, typeKey) in listOf(
            Triple("socialContext", "generalContext", "contextType"),
            Triple("social_context", "general_context", "context_type"),
            Triple("socialContext", "", "contextType"),
            Triple("social_context", "", "context_type"),
        )) {
            val type = JSONObject().put(typeKey, "Retweet").put("text", "任意语言")
            val social = if (generalKey.isEmpty()) type else JSONObject().put(generalKey, type)
            val content = item().put(socialKey, social)
            val androidEntry = JSONObject().put("entry_id", "tweet-1").put("content", JSONObject().put("content", content))
            assertEquals(1, filter().filter(envelope(androidEntry)).events.size, socialKey + generalKey)
        }
    }

    @Test fun `Android tweetResult and outer item context are recognized`() {
        val content = JSONObject().put("tweetResult", JSONObject().put("result", tweet()))
        val outer = JSONObject().put("content", content).put("socialContext", JSONObject().put("generalContext", JSONObject().put("contextType", "Retweet")))
        assertEquals(1, filter().filter(envelope(entry(outer))).events.size)
    }

    @Test fun `legacy details and root repost references support visibility wrappers`() {
        for (location in listOf("legacy", "details", "root")) {
            for (wrapped in listOf(false, true)) {
                val original = tweet()
                val body = if (location == "root") original else original.optJSONObject(location)
                    ?: JSONObject().also { original.put(location, it) }
                body.put("retweeted_status_result", JSONObject().put("result", tweet("2")))
                val result = if (wrapped) JSONObject().put("__typename", "TweetWithVisibilityResults").put("tweet", original) else original
                assertEquals("retweeted_status_result", filter().filter(envelope(entry(item(result)))).events.single().rule)
            }
        }
        val legacy = tweet().put("retweeted_status", tweet("2"))
        assertEquals(1, filter().filter(envelope(entry(item(legacy)))).events.size)
    }

    @Test fun `null empty and malformed repost references fail open`() {
        for (reference in listOf(JSONObject.NULL, "Retweet", JSONObject(), JSONObject().put("result", JSONObject.NULL), JSONObject().put("result", JSONObject()))) {
            val original = tweet()
            original.getJSONObject("legacy").put("retweeted_status_result", reference)
            original.put("retweeted_status", JSONObject.NULL)
            val input = envelope(entry(item(original)))
            assertEquals(input, filter().filter(input).json)
        }
    }

    @Test fun `viewer actions counts and repost words never identify a repost`() {
        val original = tweet().put("retweeted", true).put("retweet_count", 999)
        original.getJSONObject("legacy").put("full_text", "RT @bob: 已转帖 reposted").put("retweeted", true)
            .put("retweet_count", 99).put("current_user_retweet", JSONObject().put("id_str", "3"))
        val input = envelope(entry(item(original)))
        assertEquals(input, filter().filter(input).json)
    }

    @Test fun `other social contexts including misleading text are preserved`() {
        for (type in listOf("Like", "Pin", "Follow", "QuoteTweet", "", "RetweetSomething")) {
            val context = JSONObject().put("contextType", type).put("text", "Alice 已转帖")
            val input = envelope(entry(item().put("socialContext", context)))
            assertEquals(input, filter().filter(input).json, type)
        }
    }

    @Test fun `quoted repost and nested social context do not remove the parent`() {
        val quoted = tweet("2").put("retweeted_status_result", JSONObject().put("result", tweet("3")))
        quoted.put("socialContext", JSONObject().put("contextType", "Retweet"))
        val parent = tweet().put("quoted_status_result", JSONObject().put("result", quoted))
        parent.put("is_quote_status", true)
        val input = envelope(entry(item(parent)))
        assertEquals(input, filter().filter(input).json)
        // A repost of a quote is still a repost.
        parent.getJSONObject("legacy").put("retweeted_status_result", JSONObject().put("result", quoted))
        assertEquals(1, filter().filter(envelope(entry(item(parent)))).events.size)
    }

    @Test fun `module items keep clean siblings and remove empty modules`() {
        val bad = JSONObject().put("entryId", "tweet-1").put("item", JSONObject().put("itemContent", repostItem()))
        val good = JSONObject().put("entryId", "tweet-2").put("item", JSONObject().put("itemContent", item(tweet("2"))))
        val module = JSONObject().put("entryId", "module-1").put("content", JSONObject().put("items", JSONArray().put(bad).put(good)))
        val output = filter().filter(envelope(module))
        assertEquals(1, output.events.size)
        assertEquals(good.toString(), entries(output.json).getJSONObject(0).getJSONObject("content").getJSONArray("items").getJSONObject(0).toString())
        module.getJSONObject("content").put("items", JSONArray().put(bad))
        assertEquals(0, entries(filter().filter(envelope(module)).json).length())
        val addition = JSONObject().put("moduleItems", JSONArray().put(bad).put(good)).toString()
        assertEquals(1, JSONObject(filter().filter(addition).json).getJSONArray("moduleItems").length())
    }

    @Test fun `unknown entries with repost context are preserved`() {
        val input = envelope(entry(JSONObject().put("socialContext", JSONObject().put("contextType", "Retweet"))))
        assertEquals(input, filter().filter(input).json)
    }

    @Test fun `promotion takes precedence and repost disabling preserves keyword filtering`() {
        val ad = repostItem().put("promotedMetadata", JSONObject())
        assertEquals("推广广告", filter().filter(envelope(entry(ad))).events.single().reason)
        val reply = tweet()
        reply.getJSONObject("legacy").put("full_text", "spam").put("in_reply_to_status_id_str", "100")
        assertEquals("正文", filter(FilterSettings(blockReposts = false)).filter(envelope(entry(item(reply)))).events.single().reason)
    }

    @Test fun `old configuration defaults off and both toggle values survive roundtrip`() {
        assertFalse(ConfigCodec.decode(JSONObject("""{"enabled":true,"onlyReplies":true}""")).blockReposts)
        for (enabled in listOf(false, true)) {
            val settings = FilterSettings(blockReposts = enabled, whitelist = setOf("alice"), customRules = "hello")
            assertEquals(settings, ConfigCodec.decode(ConfigCodec.encode(settings)))
        }
    }
}
