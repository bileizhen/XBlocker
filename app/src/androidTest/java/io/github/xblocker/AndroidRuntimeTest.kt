package io.github.xblocker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.core.ConfigCodec
import io.github.xblocker.core.FilterSettings
import io.github.xblocker.core.RuleEngine
import io.github.xblocker.core.TimelineFilter
import io.github.xblocker.core.Tweet
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeTest {
    @Test fun androidUrlEntitiesMatchLiteralAndRegexWithoutReadingQuotedLinks() {
        val tweet = JSONObject().put("rest_id", "1").put("legacy", JSONObject())
            .put("details", JSONObject().put("full_text", "Join https://t.co/short"))
        val links = org.json.JSONArray().put(JSONObject().put("expanded_url", "https://EXAMPLE.org/post"))
        fun input() = JSONObject().put("entries", org.json.JSONArray().put(JSONObject().put("entry_id", "tweet-1")
            .put("content", JSONObject().put("content", JSONObject().put("tweet_results", JSONObject().put("result", tweet)))))).toString()
        for (rule in listOf("example.org", "/example\\.org/i")) {
            val settings = FilterSettings(cloudEnabled = false, customRules = rule, blockKeywordTweets = true)
            tweet.put("url_entities", links)
            val source = input()
            assertEquals(1, TimelineFilter(RuleEngine(settings, "")).filter(source, "SearchTimeline").events.size)
            assertEquals(source, TimelineFilter(RuleEngine(settings.copy(blockKeywordTweets = false), "")).filter(source).json)
            tweet.remove("url_entities")
            tweet.put("quoted_tweet_results", JSONObject().put("result", JSONObject().put("url_entities", links)))
            val quoted = input()
            assertEquals(quoted, TimelineFilter(RuleEngine(settings, "")).filter(quoted).json)
        }
    }
    @Test fun historyRetainsOneThousandRecordsWithAndroidJson() {
        var records = org.json.JSONArray()
        repeat(11) { batch ->
            val incoming = org.json.JSONArray()
            repeat(100) { incoming.put(JSONObject().put("id", "${batch * 100 + it}")) }
            records = io.github.xblocker.data.HistoryStore.append(records, incoming, batch.toLong()).records
        }
        assertEquals(1000, records.length())
        assertEquals("100", records.getJSONObject(0).getString("id"))
        assertEquals("1099", records.getJSONObject(999).getString("id"))
    }
    @Test fun keywordTweetScopePersistsAndMatchesExpandedLinksOnAndroid() {
        val repository = io.github.xblocker.data.Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = repository.settings()
        try {
            assertFalse(ConfigCodec.decode(JSONObject()).blockKeywordTweets)
            val settings = FilterSettings(cloudEnabled = false, customRules = "/example\\.org/i", blockKeywordTweets = true)
            repository.save(settings)
            assertEquals(settings, repository.settings())
            val input = """{"entries":[{"entryId":"tweet-1","content":{"itemContent":{"tweet_results":{"result":{"rest_id":"1","legacy":{"full_text":"https://t.co/short","entities":{"urls":[{"expanded_url":"https://EXAMPLE.org/post"}]}}}}}}}]}"""
            assertEquals(1, TimelineFilter(RuleEngine(repository.settings(), "")).filter(input).events.size)
            repository.save(settings.copy(blockKeywordTweets = false))
            assertEquals(input, TimelineFilter(RuleEngine(repository.settings(), "")).filter(input).json)
        } finally { repository.save(original) }
    }
    @Test fun cloudSnapshotCompilesOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rules = context.assets.open("keywords.txt").bufferedReader().use { it.readText() }
        val engine = RuleEngine(FilterSettings(), rules)
        assertTrue(engine.count > 500)
        assertNotNull(RuleEngine(FilterSettings(customRules = "xblocker-test"), "").match(Tweet("1", "xblocker-test", isReply = true)))
    }
    @Test fun timelineFilteringUsesAndroidJsonCorrectly() {
        val input = """{"entries":[{"entryId":"tweet-1","content":{"itemContent":{"tweet_results":{"result":{"rest_id":"1","legacy":{"full_text":"xblocker-test","in_reply_to_status_id_str":"2"}}}}}},{"entryId":"cursor-bottom-1","content":{"cursorType":"Bottom","value":"keep"}}]}"""
        val result = TimelineFilter(RuleEngine(FilterSettings(customRules = "xblocker-test"), "")).filter(input)
        assertEquals(1, result.events.size)
        assertEquals("cursor-bottom-1", JSONObject(result.json).getJSONArray("entries").getJSONObject(0).getString("entryId"))
    }
    @Test fun codecPreservesEmptyCategoryOverrides() {
        val settings = FilterSettings(disabledCategories = emptySet(), whitelist = setOf("alice"))
        assertEquals(settings, ConfigCodec.decode(ConfigCodec.encode(settings)))
    }

    @Test fun autoUpdatePreferenceRespectsEditionAndCanBeDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = io.github.xblocker.data.Repository(context)
        val prefs = context.getSharedPreferences("xblocker", android.content.Context.MODE_PRIVATE)
        val hadPreference = prefs.contains("autoUpdate")
        val original = prefs.getBoolean("autoUpdate", true)
        try {
            repository.setAutoUpdate(true)
            assertEquals(!BuildConfig.FPA_COMPAT, repository.autoUpdate())
            repository.setAutoUpdate(false)
            assertFalse(repository.autoUpdate())
        } finally {
            if (hadPreference) repository.setAutoUpdate(original)
            else prefs.edit().remove("autoUpdate").commit()
        }
    }
    @Test fun x1216DetailsAndReplyReferencesUseAndroidJsonCorrectly() {
        val input = """{"entries":[
          {"entry_id":"tweet-1","content":{"content":{"tweet_results":{"result":{
            "rest_id":"1","legacy":{},"details":{"full_text":"xblocker-test"},"reply_to_results":{"rest_id":null}
          }}}}},
          {"entry_id":"tweet-2","content":{"content":{"tweet_results":{"result":{
            "rest_id":"2","legacy":{},"details":{"full_text":"xblocker-test"},"reply_to_results":{"rest_id":"1"}
          }}}}}
        ]}"""
        val output = TimelineFilter(RuleEngine(FilterSettings(customRules = "xblocker-test"), "")).filter(input)
        assertTrue(output.recognized)
        assertEquals("2", output.events.single().id)
        assertEquals("tweet-1", JSONObject(output.json).getJSONArray("entries").getJSONObject(0).getString("entry_id"))
    }
}
