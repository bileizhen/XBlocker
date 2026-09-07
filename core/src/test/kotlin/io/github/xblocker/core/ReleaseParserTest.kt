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
        assertEquals("XBlocker.apk", result.assetName)
        assertEquals("https://github.com/bileizhen/XBlocker/releases/download/v0.2.0/XBlocker.apk", result.downloadUrl)
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

    private fun list(vararg jsons: JSONObject) = JSONArray().also { array -> jsons.forEach { array.put(it) } }.toString()

    @Test fun `pre-release channel offers rc but not equal or older`() {
        val rc = release("v0.2.6-rc.1").put("prerelease", true)
        assertEquals("0.2.6-rc.1", ReleaseParser.newestRelease(list(rc, release("v0.2.5")), "0.2.5")!!.version)
        assertNull(ReleaseParser.newestRelease(list(rc, release("v0.2.5")), "0.2.6-rc.1"))
        assertNull(ReleaseParser.newestRelease(list(rc, release("v0.2.5")), "0.2.6"))
    }
    @Test fun `pre-release suffix ordering follows semver subset`() {
        assertEquals("0.2.6-rc.2", ReleaseParser.newestRelease(
            list(release("v0.2.6-rc.1").put("prerelease", true), release("v0.2.6-rc.2").put("prerelease", true)), "0.2.5")!!.version)
        // A final release outranks any rc of the same numbers.
        assertEquals("0.2.6", ReleaseParser.newestRelease(
            list(release("v0.2.6-rc.2").put("prerelease", true), release("v0.2.6")), "0.2.6-rc.1")!!.version)
        // rc outranks beta of the same numbers.
        assertEquals("0.2.6-rc.1", ReleaseParser.newestRelease(
            list(release("v0.2.6-beta.3").put("prerelease", true), release("v0.2.6-rc.1").put("prerelease", true)), "0.2.5")!!.version)
        // A beta is not an update for an rc user, and the same rc is not either.
        assertNull(ReleaseParser.newestRelease(
            list(release("v0.2.6-beta.2").put("prerelease", true)), "0.2.6-rc.1"))
    }
    @Test fun `stable channel still ignores pre-releases`() {
        assertNull(ReleaseParser.newerRelease(release("v0.2.6-rc.1").put("prerelease", true).toString(), "0.2.5"))
    }
    @Test fun `list parser skips drafts and unparseable newest entries`() {
        assertEquals("0.2.5", ReleaseParser.newestRelease(
            list(release("v0.9.9").put("draft", true), release("v0.2.5")), "0.2.4")!!.version)
        assertEquals("0.2.5", ReleaseParser.newestRelease(
            list(JSONObject().put("tag_name", "latest"), release("v0.2.5")), "0.2.4")!!.version)
        assertNull(ReleaseParser.newestRelease(list(JSONObject()), "0.2.4"))
    }
    @Test fun `unknown pre-release suffixes are rejected`() {
        assertNull(ReleaseParser.newestRelease(list(release("v0.2.6-dev.1")), "0.2.5"))
    }
}
