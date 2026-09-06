package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Regression test against the real upstream keyword library shipped in assets. */
class CloudRulesTest {
    private val cloud = javaClass.classLoader!!.getResourceAsStream("keywords.txt")!!.readBytes().decodeToString()

    private fun tweetResult(id: String, text: String, reply: Boolean, name: String = "某人", handle: String = "someone"): JSONObject = JSONObject()
        .put("__typename", "Tweet").put("rest_id", id)
        .put("legacy", JSONObject().put("full_text", text).put("in_reply_to_status_id_str", if (reply) "100" else JSONObject.NULL))
        .put("core", JSONObject().put("user_results", JSONObject().put("result", JSONObject()
            .put("__typename", "User").put("core", JSONObject().put("name", name).put("screen_name", handle)))))

    private fun detail(vararg results: JSONObject): String = JSONObject().put("data", JSONObject().put("threaded_conversation_with_injections_v2", JSONObject()
        .put("instructions", JSONArray().put(JSONObject().put("type", "TimelineAddEntries").put("entries", JSONArray(results.mapIndexed { i, r ->
            JSONObject().put("entryId", "tweet-$i").put("sortIndex", "$i")
                .put("content", JSONObject().put("entryType", "TimelineTimelineItem")
                    .put("itemContent", JSONObject().put("entryType", "TimelineTimelineItem").put("tweet_results", JSONObject().put("result", r))))
        })))))).toString()

    private fun filter(settings: FilterSettings = FilterSettings()) = TimelineFilter(RuleEngine(settings, cloud))

    @Test fun `real spam replies from device screenshot are blocked`() {
        val engine = RuleEngine(FilterSettings(), cloud)
        assertTrue(engine.count > 300, "cloud rules compiled: ${engine.count}")
        val out = filter().filter(detail(
            tweetResult("1", "应该没人比我玩的开了吧", true),
            tweetResult("2", "我福不黑不信你看", true),
            tweetResult("3", "今天天气不错", true)))
        assertTrue(out.recognized)
        assertEquals(2, out.events.size, out.events.toString())
        assertEquals("正文", out.events[0].reason)
    }

    @Test fun `plain timeline text with emoji separators still matches`() {
        val out = filter().filter(detail(tweetResult("1", "应\u2062该没人\uFE0F比我玩的开了吧❤️", true)))
        assertEquals(1, out.events.size)
    }

    @Test fun `promoted reply with spam body hits keyword first`() {
        val entry = tweetResult("1", "我福不黑不信你看", true)
        val out = filter().filter(JSONObject().put("data", JSONObject().put("instructions", JSONArray().put(JSONObject()
            .put("entries", JSONArray().put(JSONObject().put("entryId", "tweet-1").put("content", JSONObject()
                .put("itemContent", JSONObject().put("promotedMetadata", JSONObject().put("advertiser_id", "9")).put("tweet_results", JSONObject().put("result", entry)))))))))
            .toString())
        assertEquals(1, out.events.size)
    }

    /** X 12.19.1 Android URT payload: tweet nested under itemContent.content.tweetResult.result. */
    @Test fun `android urt nesting is filtered`() {
        val tweet = tweetResult("1", "应该没人比我玩的开了吧", true)
        val urt = JSONObject().put("entryId", "tweet-1").put("content", JSONObject().put("entryType", "TimelineTimelineItem")
            .put("itemContent", JSONObject().put("entryType", "TimelineTimelineItem")
                .put("content", JSONObject().put("__typename", "TimelineTweet").put("id", "1")
                    .put("tweetResult", JSONObject().put("result", tweet)))))
        val out = filter().filter(envelopeOf(urt))
        assertTrue(out.recognized)
        assertEquals(1, out.events.size, out.events.toString())
    }

    /** Android serves status fields on the result itself, without a legacy wrapper. */
    @Test fun `android urt flat tweet without legacy is filtered`() {
        val flat = JSONObject().put("__typename", "Tweet").put("rest_id", "2").put("id_str", "2")
            .put("full_text", "我福不黑不信你看").put("in_reply_to_status_id_str", "100")
            .put("core", JSONObject().put("user_results", JSONObject().put("result", JSONObject()
                .put("core", JSONObject().put("name", "某人").put("screen_name", "someone")))))
        val urt = JSONObject().put("content", JSONObject().put("itemContent", JSONObject()
            .put("content", JSONObject().put("tweetResult", JSONObject().put("result", flat)))))
        assertEquals(1, filter().filter(envelopeOf(urt)).events.size)
    }

    /** Tweet detail threads use conversationComponents[].conversationTweetComponent.tweet. */
    @Test fun `conversation thread components are filtered`() {
        val thread = JSONObject().put("entryId", "conversation-1").put("content", JSONObject().put("entryType", "TimelineTimelineModule")
            .put("items", JSONArray().put(JSONObject().put("entryId", "tweet-1").put("item", JSONObject()
                .put("itemContent", JSONObject().put("conversationComponents", JSONArray().put(JSONObject()
                    .put("conversationTweetComponent", JSONObject().put("tweet", JSONObject()
                        .put("tweetResult", JSONObject().put("result", tweetResult("1", "我福不黑不信你看", true))))))))))))
        val out = filter().filter(envelopeOf(thread))
        assertEquals(1, out.events.size, out.events.toString())
    }

    /** Android marks ads with tweetPromotedMetadata inside the same content wrapper as tweetResult. */
    @Test fun `android urt promoted metadata is detected`() {
        val urt = JSONObject().put("entryId", "tweet-1").put("content", JSONObject()
            .put("itemContent", JSONObject().put("content", JSONObject()
                .put("tweetPromotedMetadata", JSONObject().put("advertiserResult", JSONObject()))
                .put("tweetResult", JSONObject().put("result", tweetResult("1", "正常内容", true))))))
        val out = filter().filter(envelopeOf(urt))
        assertEquals(1, out.events.size)
        assertEquals("推广广告", out.events[0].reason)
    }

    /** The Android payload links the author through core.user_result (singular). */
    @Test fun `android user_result handle feeds whitelist and events`() {
        val tweet = tweetResult("1", "正常内容", true, handle = "spammer")
        tweet.getJSONObject("core").remove("user_results")
        tweet.getJSONObject("core").put("user_result", JSONObject().put("result", JSONObject()
            .put("core", JSONObject().put("name", "某人").put("screen_name", "spammer"))))
        val out = filter(FilterSettings(whitelist = setOf("spammer"))).filter(envelopeOf(JSONObject().put("entryId", "tweet-1")
            .put("content", JSONObject().put("itemContent", JSONObject().put("content", JSONObject()
                .put("tweetResult", JSONObject().put("result", tweet)))))))
        assertEquals(0, out.events.size)
        val blocked = filter().filter(envelopeOf(JSONObject().put("entryId", "tweet-1")
            .put("content", JSONObject().put("itemContent", JSONObject().put("content", JSONObject()
                .put("tweetResult", JSONObject().put("result", tweetResult("2", "我福不黑不信你看", true, handle = "spammer2"))))))))
        assertEquals("spammer2", blocked.events[0].handle)
    }

    /** Thread continuations without in_reply_to are still recognized via conversation_id_str. */
    @Test fun `conversation id marks thread replies`() {
        val threadReply = tweetResult("200", "我福不黑不信你看", false)
        threadReply.getJSONObject("legacy").put("conversation_id_str", "100")
        val out = filter().filter(envelopeOf(JSONObject().put("entryId", "tweet-200")
            .put("content", JSONObject().put("itemContent", JSONObject().put("content", JSONObject()
                .put("tweetResult", JSONObject().put("result", threadReply)))))))
        assertEquals(1, out.events.size)
    }

    private fun envelopeOf(vararg entries: JSONObject): String = JSONObject().put("data", JSONObject()
        .put("timeline", JSONObject().put("instructions", JSONArray().put(JSONObject()
            .put("type", "TimelineAddEntries").put("entries", JSONArray(entries.toList())))))).toString()
}
