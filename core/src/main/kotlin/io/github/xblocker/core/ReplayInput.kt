package io.github.xblocker.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream

object ReplayInput {
    /** On oversize/error replay every byte consumed before handing control back to the host. */
    fun transform(source: InputStream, transform: (String) -> String): InputStream {
        val consumed = ByteArrayOutputStream()
        try {
            val buffer = ByteArray(8192)
            while (consumed.size() <= TimelineFilter.MAX_CHARS) {
                val read = source.read(buffer, 0, minOf(buffer.size, TimelineFilter.MAX_CHARS + 1 - consumed.size()))
                if (read < 0) {
                    val bytes = consumed.toByteArray()
                    val text = bytes.toString(Charsets.UTF_8)
                    // Preserve non-UTF8/binary sources byte for byte.
                    val output = if (text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) transform(text).toByteArray(Charsets.UTF_8) else bytes
                    return object : ByteArrayInputStream(output) { override fun close() { source.close(); super.close() } }
                }
                if (read == 0) break
                consumed.write(buffer, 0, read)
                if (consumed.size() == read && buffer.take(read).firstOrNull { it.toInt().toChar() !in " \r\n\t" }?.toInt()?.toChar() != '{') break
            }
        } catch (_: Exception) { /* Replay the prefix; host owns subsequent read/error handling. */ }
        return SequenceInputStream(ByteArrayInputStream(consumed.toByteArray()), source)
    }
}
