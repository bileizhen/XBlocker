package io.github.xblocker.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.jupiter.api.Test
import kotlin.test.*

class ReplayInputTest {
    @Test fun `oversize streams replay every byte`() {
        val bytes = ("{" + "a".repeat(TimelineFilter.MAX_CHARS + 100)).toByteArray()
        assertContentEquals(bytes, ReplayInput.transform(ByteArrayInputStream(bytes)) { error("Must not transform") }.readBytes())
    }
    @Test fun `binary input stays unchanged`() {
        val bytes = byteArrayOf(0, 1, 2, -1, -2)
        assertContentEquals(bytes, ReplayInput.transform(ByteArrayInputStream(bytes)) { error("Must not transform") }.readBytes())
    }
    @Test fun `valid input transformed and close reaches source`() {
        var closed = false
        val source = object : ByteArrayInputStream("{\"a\":1}".toByteArray()) { override fun close() { closed = true } }
        val result = ReplayInput.transform(source) { "{\"a\":2}" }
        assertEquals("{\"a\":2}", result.readBytes().decodeToString())
        result.close(); assertTrue(closed)
    }
    @Test fun `read error does not discard consumed prefix`() {
        val source = object : InputStream() {
            var position = 0; var failed = false
            val bytes = "{\"value\":123}".toByteArray()
            override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position > 0 && !failed) { failed = true; throw IOException("transient") }
                if (position >= bytes.size) return -1
                val n = minOf(3, len, bytes.size - position); bytes.copyInto(b, off, position, position + n); position += n; return n
            }
        }
        assertEquals("{\"value\":123}", ReplayInput.transform(source) { it }.readBytes().decodeToString())
    }
}
