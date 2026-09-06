package io.github.xblocker.core

import java.net.URI

/** Uses the host's OkHttp without packaging a second copy of it in the module. */
class GraphQlResponseFilter(loader: ClassLoader, private val transform: (String) -> String) {
    private val response = loader.loadClass("okhttp3.Response")
    private val body = loader.loadClass("okhttp3.ResponseBody")
    private val mediaType = loader.loadClass("okhttp3.MediaType")
    private val builder = loader.loadClass("okhttp3.Response\$Builder")
    private val request = loader.loadClass("okhttp3.Request")
    private val getRequest = response.getMethod("request")
    private val getUrl = request.getMethod("url")
    private val getCode = response.getMethod("code")
    private val getHeader = response.getMethod("header", String::class.java)
    private val getBody = response.getMethod("body")
    private val peekBody = response.getMethod("peekBody", Long::class.javaPrimitiveType)
    private val bytes = body.getMethod("bytes")
    private val contentType = body.getMethod("contentType")
    private val contentLength = body.getMethod("contentLength")
    private val close = body.getMethod("close")
    private val create = body.getMethod("create", mediaType, ByteArray::class.java)
    private val newBuilder = response.getMethod("newBuilder")
    private val setBody = builder.getMethod("body", body)
    private val removeHeader = builder.getMethod("removeHeader", String::class.java)
    private val build = builder.getMethod("build")

    /** Peek leaves the original source readable on skips, parse errors and oversized bodies. */
    fun filter(original: Any): Any {
        val uri = URI(getUrl.invoke(getRequest.invoke(original)).toString())
        if (uri.scheme != "https" || uri.host !in setOf("api.x.com", "api.twitter.com") ||
            !uri.path.startsWith("/graphql/") || (getCode.invoke(original) as Int) !in 200..299) return original
        val encoding = getHeader.invoke(original, "Content-Encoding") as? String
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return original
        val originalBody = getBody.invoke(original) ?: return original
        val type = contentType.invoke(originalBody) ?: return original
        val mime = type.toString().substringBefore(';').trim()
        if (!mime.equals("application/json", ignoreCase = true)) return original
        if ((contentLength.invoke(originalBody) as Long) > TimelineFilter.MAX_CHARS) return original
        val peek = peekBody.invoke(original, TimelineFilter.MAX_CHARS.toLong() + 1)
        val input = try { bytes.invoke(peek) as ByteArray } finally { close.invoke(peek) }
        if (input.size > TimelineFilter.MAX_CHARS) return original
        val text = input.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(input)) return original
        val output = transform(text)
        if (output == text) return original
        val replacement = create.invoke(null, type, output.toByteArray(Charsets.UTF_8))
        val updated = try {
            val b = newBuilder.invoke(original)
            setBody.invoke(b, replacement)
            removeHeader.invoke(b, "Content-Length")
            build.invoke(b)
        } catch (e: Exception) {
            close.invoke(replacement)
            throw e
        }
        // Replacement is complete before releasing the original network source.
        runCatching { close.invoke(originalBody) }
        return updated
    }
}
