package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.*

class ReleaseParserTest {
    private fun release(tag: String = "v0.2.0") = JSONObject().put("tag_name", tag)
        .put("html_url", "https://github.com/bileizhen/XBlocker/releases/tag/$tag")
        .put("body", "更新说明").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "XBlocker.apk")
            .put("browser_download_url", "https://github.com/bileizhen/XBlocker/releases/download/$tag/XBlocker.apk")))

    @Test fun `new installable stable release is offered`() {
        val result = ReleaseParser.newerRelease(release().toString(), "0.1.0")!!
        assertEquals("0.2.0", result.version)
        assertEquals("更新说明", result.notes)
    }
    @Test fun `versions compare numerically and do not offer downgrades`() {
        assertNotNull(ReleaseParser.newerRelease(release("v0.10.0").toString(), "0.9.0"))
        assertNull(ReleaseParser.newerRelease(release().toString(), "0.2.0"))
        assertNull(ReleaseParser.newerRelease(release().toString(), "1.0.0"))
    }
    @Test fun `drafts prereleases and source only releases are ignored`() {
        for (json in listOf(release().put("draft", true), release().put("prerelease", true),
            release("v0.3.0-beta.1"), release().put("assets", JSONArray()))) {
            assertNull(ReleaseParser.newerRelease(json.toString(), "0.1.0"))
        }
    }
    @Test fun `foreign release or download links are ignored`() {
        val foreignPage = release().put("html_url", "https://example.com/update")
        val foreignAsset = release().put("assets", JSONArray().put(JSONObject().put("name", "app.apk")
            .put("browser_download_url", "https://example.com/app.apk")))
        assertNull(ReleaseParser.newerRelease(foreignPage.toString(), "0.1.0"))
        assertNull(ReleaseParser.newerRelease(foreignAsset.toString(), "0.1.0"))
    }
    @Test fun `invalid and overflowing versions are ignored`() {
        for (tag in listOf("latest", "v1.0", "v1.02.0", "v999999999999999999999.0.0"))
            assertNull(ReleaseParser.newerRelease(release(tag).toString(), "0.1.0"))
        assertNull(ReleaseParser.newerRelease(release().toString(), "unknown"))
    }
    @Test fun `release notes are bounded`() {
        assertEquals(6000, ReleaseParser.newerRelease(release().put("body", "a".repeat(9000)).toString(), "0.1.0")!!.notes.length)
    }
}
