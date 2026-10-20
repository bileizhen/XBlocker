package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

class SspTimelineFilterTest {
    private fun entry(id: String, type: String) = JSONObject().put("entryId", id)
        .put("content", JSONObject().put("itemContent", JSONObject().put("content", JSONObject().put("__typename", type))))
    private fun envelope(vararg entries: JSONObject) = JSONObject().put("data", JSONObject().put("entries", JSONArray(entries.toList()))).toString()
    private fun filter(settings: FilterSettings = FilterSettings()) = TimelineFilter(RuleEngine(settings, ""))

    @Test fun `exact SSP slot is removed while ordinary items and cursors survive`() {
        val cursor = JSONObject().put("entryId", "cursor-bottom").put("content", JSONObject().put("cursorType", "Bottom").put("value", "opaque"))
        val result = filter().filter(envelope(entry("a", "TimelineRtbImageAd"), entry("b", "TimelineTweet"), cursor))
        assertEquals("视频广告", result.events.single().reason)
        assertEquals("a", result.events.single().id)
        val retained = JSONObject(result.json).getJSONObject("data").getJSONArray("entries")
        assertEquals(listOf("b", "cursor-bottom"), (0 until retained.length()).map { retained.getJSONObject(it).getString("entryId") })
        assertEquals("opaque", retained.getJSONObject(1).getJSONObject("content").getString("value"))
    }
    @Test fun `SSP obeys promotion and master switches independently of reply only mode`() {
        val input = envelope(entry("a", "TimelineRtbImageAd"))
        assertEquals(1, filter().filter(input).events.size)
        assertEquals(input, filter(FilterSettings(blockPromoted = false)).filter(input).json)
        assertEquals(input, filter(FilterSettings(enabled = false)).filter(input).json)
    }
    @Test fun `advertising words unknown models and quoted ad objects are not SSP signals`() {
        val normal = entry("a", "TimelineTweet").put("text", "广告 TimelineRtbImageAd Cambly")
        normal.getJSONObject("content").getJSONObject("itemContent").put("quoted_status_result", JSONObject().put("__typename", "TimelineRtbImageAd"))
        val input = envelope(normal, entry("b", "SomeFutureAdShape"))
        assertEquals(input, filter().filter(input).json)
        assertTrue(filter().filter(input).events.isEmpty())
    }
}
