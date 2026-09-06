package io.github.xblocker.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class GraphQlResponseFilterTest {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private fun response(body: ResponseBody, url: String = "https://api.x.com/graphql/query/TweetDetail") =
        Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_2)
            .code(200).message("OK").header("Content-Length", "999").header("X-Test", "keep")
            .body(body).build()
    private fun adapter(transform: (String) -> String) = GraphQlResponseFilter(javaClass.classLoader, transform)

    @Test fun `filters a network timeline and preserves status headers and cursors`() {
        val input = """{"data":{"timeline":{"instructions":[{"entries":[
            {"entryId":"tweet-1","content":{"itemContent":{"tweet_results":{"result":{
              "rest_id":"1","legacy":{"full_text":"spam","in_reply_to_status_id_str":"2"}
            }}}}},
            {"entryId":"cursor-bottom","content":{"cursorType":"Bottom","value":"opaque"}}
        ]}]}}}"""
        val timeline = TimelineFilter(RuleEngine(FilterSettings(), "spam"))
        val original = response(input.toResponseBody(jsonType))
        val result = adapter { timeline.filter(it).json }.filter(original) as Response
        assertNotSame(original, result)
        assertEquals(200, result.code)
        assertEquals("keep", result.header("X-Test"))
        assertNull(result.header("Content-Length"))
        val output = result.body!!.string()
        assertFalse(output.contains("tweet-1"))
        assertTrue(output.contains("cursor-bottom"))
        assertTrue(output.contains("opaque"))
        assertEquals(1, timeline.stats.blocked.get())
    }

    @Test fun `unchanged and failed transformations leave original body readable`() {
        for (fail in listOf(false, true)) {
            val original = response("{\"data\":{}}".toResponseBody(jsonType))
            val filter = adapter { if (fail) error("failed") else it }
            if (fail) assertFails { filter.filter(original) } else assertSame(original, filter.filter(original))
            assertEquals("{\"data\":{}}", original.body!!.string())
        }
    }

    @Test fun `unrelated domains paths encodings and media types are skipped`() {
        val base = response("{}".toResponseBody(jsonType))
        val inputs = listOf(
            response("{}".toResponseBody(jsonType), "https://example.com/graphql/a/TweetDetail"),
            response("{}".toResponseBody(jsonType), "https://api.x.com/1.1/direct_messages/events/list.json"),
            base.newBuilder().header("Content-Encoding", "gzip").build(),
            base.newBuilder().code(500).build(),
            response("{}".toResponseBody("application/octet-stream".toMediaType()))
        )
        for (original in inputs) {
            assertSame(original, adapter { error("must skip") }.filter(original))
            original.close()
        }
    }

    @Test fun `unknown length oversized and invalid UTF8 bodies remain byte exact`() {
        val inputs = listOf(ByteArray(TimelineFilter.MAX_CHARS + 10) { 'a'.code.toByte() }, byteArrayOf(123, -1, 125))
        for (input in inputs) {
            val original = response(object : ResponseBody() {
                val data = Buffer().write(input)
                override fun contentType() = jsonType
                override fun contentLength() = -1L
                override fun source(): BufferedSource = data
            })
            assertSame(original, adapter { error("must skip") }.filter(original))
            assertContentEquals(input, original.body!!.bytes())
        }
    }

    @Test fun `replacement closes network source but a peek does not`() {
        for (replace in listOf(false, true)) {
            var closed = false
            val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
                override fun close() { closed = true; super.close() }
            }.buffer()
            val original = response(object : ResponseBody() {
                override fun contentType() = jsonType
                override fun contentLength() = -1L
                override fun source(): BufferedSource = source
            })
            val result = adapter { if (replace) "{\"filtered\":true}" else it }.filter(original) as Response
            assertEquals(replace, closed)
            result.close()
            assertTrue(closed)
        }
    }
}
