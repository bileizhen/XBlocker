package io.github.xblocker.download

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** This endpoint accepts public X CDN media, never arbitrary web downloads or authenticated requests. */
internal object MediaDownloadPolicy {
    enum class Kind(val mime: String, val extension: String, val maxBytes: Long) {
        IMAGE("image/jpeg", "jpg", 32L * 1024 * 1024),
        VIDEO("video/mp4", "mp4", 512L * 1024 * 1024),
    }

    fun kind(type: String): Kind = when (type.lowercase(Locale.ROOT)) {
        "image", "photo" -> Kind.IMAGE
        "video", "animated_gif" -> Kind.VIDEO
        else -> throw IllegalArgumentException("Unsupported media type")
    }

    fun validateUrl(raw: String, kind: Kind): URI {
        require(raw.length in 1..4096 && raw.none { it.isWhitespace() || it.isISOControl() }) { "Invalid media URL" }
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443)) { "Only HTTPS CDN URLs are supported" }
        val path = uri.path.orEmpty()
        when (kind) {
            Kind.IMAGE -> require(uri.host.equals("pbs.twimg.com", true) && path.startsWith("/media/") &&
                path.length > "/media/".length) { "Invalid photo CDN URL" }
            Kind.VIDEO -> require(uri.host.equals("video.twimg.com", true) &&
                path.endsWith(".mp4", true) && path.length > 5) { "Only direct X MP4 URLs are supported" }
        }
        return uri
    }

    fun validateId(value: String): String {
        require(value.length in 1..80 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
            "Invalid media identifier"
        }
        return value
    }

    fun key(url: String, tweetId: String, mediaId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$tweetId\n$mediaId\n$url".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun contentType(header: String?, kind: Kind): String {
        val value = header.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        require(when (kind) {
            Kind.IMAGE -> value in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
            Kind.VIDEO -> value == "video/mp4"
        }) { "Unexpected media content type" }
        return value
    }

    fun extension(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        else -> throw IllegalArgumentException("Unsupported media content type")
    }

    fun verifyHeader(bytes: ByteArray, count: Int, mime: String) {
        fun ascii(offset: Int, text: String): Boolean = count >= offset + text.length &&
            text.indices.all { bytes[offset + it].toInt().and(255) == text[it].code }
        val valid = when (mime) {
            "image/jpeg" -> count >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            "image/png" -> count >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            "image/webp" -> ascii(0, "RIFF") && ascii(8, "WEBP")
            "image/gif" -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
            "video/mp4" -> ascii(4, "ftyp")
            else -> false
        }
        require(valid) { "Media bytes do not match their content type" }
    }
}
