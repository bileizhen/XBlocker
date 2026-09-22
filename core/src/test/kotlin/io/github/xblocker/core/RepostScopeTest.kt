package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

class RepostScopeTest {
    // Minimized schema from the Android HomeTimeline and UserProfileRepostsTimelineQuery queries.
    private fun repost() = JSONObject("""{
        "entry_id":"tweet-1","content":{"content":{"tweet_results":{"result":{
            "rest_id":"1","details":{"full_text":"ordinary post"},
            "legacy":{"repostedStatusResults":{"result":{"rest_id":"2"}}}
        }}}}
    }""")
    private fun timeline(entry: JSONObject = repost()) = JSONObject().put("timeline", JSONObject()
        .put("instructions", JSONArray().put(JSONObject().put("entries", JSONArray().put(entry)))))
    private fun envelope(key: String = "timeline_response") = JSONObject().put("data", JSONObject().put(key, timeline())).toString()
    private fun filter(preserve: Boolean = true, block: Boolean = true) =
        TimelineFilter(RuleEngine(FilterSettings(blockReposts = block, preserveProfileReposts = preserve), ""))

    @Test fun `profile exception leaves Home requests filtering the shared timeline envelope`() {
        val input = envelope()
        for (operation in listOf("HomeTimeline", "HomeTimelineLatest", "HomeLatestTimeline", "HomeTimelineSubscriptions")) {
            assertEquals("时间线转帖", filter().filter(input, operation).events.single().reason, operation)
        }
        for (operation in listOf("UserTweets", "UserTweetsAndReplies", "UserMedia",
            "UserWithProfileTweetsQueryV2", "UserProfileRepostsTimelineQuery", "UserProfileRepliesTimelineQuery",
            "UserProfileMediaTimelineQuery", "ProfileUserPromotedTimelineQuery", "ImmersiveViewerProfileMixerTimeline")) {
            val output = filter().filter(input, operation)
            assertTrue(output.recognized, operation)
            assertTrue(output.events.isEmpty(), operation)
            assertEquals(input, output.json, operation)
            assertEquals(1, filter(preserve = false).filter(input, operation).events.size, operation)
        }
    }

    @Test fun `profile envelopes preserve reposts including parser fallbacks and conflicting request hints`() {
        for (key in listOf("user", "user_result")) {
            for (timelineKey in listOf("timeline_response", "timeline_v2", "timeline")) {
                val input = JSONObject().put("data", JSONObject().put(key, JSONObject().put("result",
                    JSONObject().put(timelineKey, timeline())))).toString()
                for (operation in listOf(null, "UserProfileRepostsTimelineQuery", "HomeTimeline")) {
                    val output = filter().filter(input, operation)
                    assertTrue(output.recognized)
                    assertEquals(input, output.json)
                    assertTrue(output.events.isEmpty())
                    assertEquals(1, filter(preserve = false).filter(input, operation).events.size)
                }
            }
        }
    }

    @Test fun `parser fallback scopes each branch independently`() {
        val profile = JSONObject().put("result", JSONObject().put("timeline_response", timeline()))
        val data = JSONObject().put("home", timeline()).put("user_result", profile)
        val input = JSONObject().put("data", data).toString()
        val output = filter().filter(input)
        assertEquals(1, output.events.size)
        assertEquals(profile.toString(), JSONObject(output.json).getJSONObject("data").getJSONObject("user_result").toString())
        for (key in listOf("home", "home_timeline", "home_latest_timeline", "creator_subscriptions_timeline")) {
            assertEquals(1, filter().filter(envelope(key)).events.size, key)
            assertEquals(envelope(key), filter().filter(envelope(key), "UserProfileRepostsTimelineQuery").json)
        }
    }

    @Test fun `profile module items survive while ads and keyword matches still filter`() {
        val ad = repost().put("entry_id", "tweet-ad")
        ad.getJSONObject("content").getJSONObject("content").put("promoted_metadata", JSONObject())
        val spam = repost().put("entry_id", "tweet-spam")
        spam.getJSONObject("content").getJSONObject("content").getJSONObject("tweet_results")
            .getJSONObject("result").getJSONObject("details").put("full_text", "spam")
            .put("in_reply_to_status_id_str", "100")
        val module = JSONObject().put("entry_id", "module-1").put("content", JSONObject()
            .put("items", JSONArray().put(repost()).put(ad).put(spam)))
        val profile = JSONObject().put("data", JSONObject().put("user_result", JSONObject().put("result",
            JSONObject().put("timeline_response", timeline(module))))).toString()
        val f = TimelineFilter(RuleEngine(FilterSettings(blockReposts = true), "spam"))
        val output = f.filter(profile, "UserProfileRepostsTimelineQuery")
        assertEquals(setOf("推广广告", "正文"), output.events.map { it.reason }.toSet())
        assertFalse(output.json.contains("tweet-ad"))
        assertFalse(output.json.contains("tweet-spam"))
        assertTrue(output.json.contains("tweet-1"))

        val addition = JSONObject().put("moduleItems", JSONArray().put(repost())).toString()
        assertEquals(addition, filter().filter(addition, "UserProfileRepostsTimelineQuery").json)
        assertEquals(1, filter(preserve = false).filter(addition, "UserProfileRepostsTimelineQuery").events.size)
        assertEquals(1, filter().filter(addition, "HomeTimelineLatest").events.size)
    }

    @Test fun `profile preservation cannot enable repost blocking by itself`() {
        for (preserve in listOf(false, true)) {
            for (operation in listOf("HomeTimeline", "UserProfileRepostsTimelineQuery")) {
                val input = envelope()
                assertEquals(input, filter(preserve, block = false).filter(input, operation).json)
            }
        }
    }

    @Test fun `old settings preserve profiles by default and all switch combinations survive roundtrip`() {
        assertTrue(ConfigCodec.decode(JSONObject("""{"blockReposts":true}""")).preserveProfileReposts)
        for (block in listOf(false, true)) {
            for (preserve in listOf(false, true)) {
                val settings = FilterSettings(blockReposts = block, preserveProfileReposts = preserve)
                assertEquals(settings, ConfigCodec.decode(ConfigCodec.encode(settings)))
            }
        }
    }
}
