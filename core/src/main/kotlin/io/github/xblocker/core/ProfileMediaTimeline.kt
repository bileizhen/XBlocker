package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** One downloadable media item from a UserProfileMediaTimelineQuery page. */
data class ProfileMediaItem(
    val tweetId: String,
    val mediaId: String,
    val type: String,
    val url: String,
    val allowDownload: Boolean,
    val sensitive: Boolean,
)

data class ProfileMediaPage(
    val items: List<ProfileMediaItem>,
    val nextCursor: String?,
    val terminated: Boolean,
    val recognized: Boolean,
    val entryCount: Int,
    val tweetCount: Int,
    val skipped: Int,
)

/** Deduplicates pages and enforces the run-wide caps. Pure state so it can be unit tested. */
class ProfileMediaRun(private val maxPages: Int = 200, private val maxMedia: Int = 5_000) {
    private val seen = HashSet<String>()
    var pages = 0; private set
    var accepted = 0; private set
    var duplicates = 0; private set
    var skipped = 0; private set
    var capped = false; private set
    var done = false; private set
    var cursor: String? = null; private set

    fun accept(page: ProfileMediaPage): List<ProfileMediaItem> {
        if (done) return emptyList()
        pages++
        skipped += page.skipped
        val fresh = ArrayList<ProfileMediaItem>()
        for (item in page.items) {
            val key = item.mediaId.ifEmpty { item.url }
            if (!seen.add(key)) { duplicates++; continue }
            if (accepted >= maxMedia) { capped = true; break }
            fresh += item
            accepted++
        }
        cursor = page.nextCursor
        if (page.terminated || page.nextCursor.isNullOrBlank()) done = true
        if (pages >= maxPages) { capped = true; done = true }
        if (accepted >= maxMedia) { capped = true; done = true }
        return fresh
    }

    fun finish() { done = true }
}

/**
 * Shape-tolerant parser for the JSON returned by UserProfileMediaTimelineQuery. It only walks
 * timeline instructions; quoted/reposted subtrees are deliberately not followed because the media
 * tab represents the profile's own posts.
 */
object ProfileMediaParser {
    const val MAX_CHARS = 8 * 1024 * 1024
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 512
    private val mediaIdPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val photoFormats = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun parse(json: String, requestedCursor: String? = null): ProfileMediaPage {
        if (json.length > MAX_CHARS) return failed()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return failed()
        if (root.has("errors") && !root.has("data")) return failed()
        val instructions = findInstructions(root) ?: return failed()
        val state = PageState(requestedCursor)
        for (i in 0 until instructions.length()) {
            val instruction = instructions.optJSONObject(i) ?: continue
            state.visitInstruction(instruction, 0)
        }
        if (state.bottomCursor != null && state.bottomCursor == requestedCursor) state.bottomCursor = null
        return ProfileMediaPage(
            items = state.items,
            nextCursor = state.bottomCursor,
            terminated = state.terminated,
            recognized = true,
            entryCount = state.entryCount,
            tweetCount = state.tweetCount,
            skipped = state.skipped,
        )
    }

    private fun failed() = ProfileMediaPage(emptyList(), null, false, false, 0, 0, 0)

    private fun findInstructions(root: JSONObject): JSONArray? {
        val data = root.optJSONObject("data")
        val results = listOfNotNull(data?.optJSONObject("user_result"), data?.optJSONObject("user"))
        for (resultHolder in results) {
            val result = resultHolder.optJSONObject("result") ?: resultHolder
            for (containerKey in listOf("timeline_response", "media_timeline_v2", "timeline_v2")) {
                val timeline = result.optJSONObject(containerKey)?.optJSONObject("timeline") ?: continue
                timeline.optJSONArray("instructions")?.let { return it }
            }
            result.optJSONObject("timeline")?.optJSONArray("instructions")?.let { return it }
        }
        // Bounded fallback for wrappers that move the timeline one level deeper.
        var visited = 0
        fun search(node: Any?, depth: Int): JSONArray? {
            if (depth > MAX_DEPTH || visited > MAX_NODES) return null
            visited++
            when (node) {
                is JSONObject -> {
                    node.optJSONArray("instructions")?.let { array ->
                        if (array.length() == 0 || looksLikeInstruction(array.optJSONObject(0))) return array
                    }
                    for (key in node.keys()) {
                        search(node.opt(key), depth + 1)?.let { return it }
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) search(node.opt(i), depth + 1)?.let { return it }
            }
            return null
        }
        return search(data, 0)
    }

    private fun looksLikeInstruction(value: JSONObject?): Boolean {
        if (value == null) return false
        val type = value.optString("__typename").ifEmpty { value.optString("type") }
        return type.startsWith("Timeline") || value.has("entries") || value.has("entry")
    }

    private class PageState(private val requestedCursor: String?) {
        val items = ArrayList<ProfileMediaItem>()
        val seen = HashSet<String>()
        var bottomCursor: String? = null
        var terminated = false
        var entryCount = 0
        var tweetCount = 0
        var skipped = 0

        fun visitInstruction(instruction: JSONObject, depth: Int) {
            if (depth > MAX_DEPTH) return
            val type = type(instruction)
            if (type == "TimelineTerminateTimeline") {
                val direction = instruction.optString("direction")
                if (direction.isEmpty() || direction.equals("Bottom", true)) terminated = true
            }
            instruction.optJSONArray("entries")?.let { entries -> for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { visitEntry(it) } }
            instruction.optJSONObject("entry")?.let { visitEntry(it) }
            instruction.optJSONArray("module_items")?.let { modules -> for (i in 0 until modules.length()) modules.optJSONObject(i)?.let { visitModuleItem(it) } }
        }

        private fun visitEntry(entry: JSONObject) {
            entryCount++
            val entryId = entry.optString("entry_id").ifEmpty { entry.optString("entryId") }
            val content = entry.optJSONObject("content") ?: entry.optJSONObject("item") ?: entry
            if (visitCursor(content, entryId)) return
            content.optJSONArray("items")?.let { moduleItems ->
                for (i in 0 until moduleItems.length()) {
                    val moduleItem = moduleItems.optJSONObject(i) ?: continue
                    visitModuleItem(moduleItem.optJSONObject("item") ?: moduleItem)
                }
                return
            }
            visitItem(content.optJSONObject("itemContent") ?: content.optJSONObject("content") ?: content)
        }

        private fun visitModuleItem(item: JSONObject) {
            val inner = item.optJSONObject("item") ?: item
            val content = inner.optJSONObject("itemContent") ?: inner.optJSONObject("content") ?: inner
            if (visitCursor(content, inner.optString("entry_id"))) return
            visitItem(content)
        }

        private fun visitCursor(content: JSONObject, entryId: String?): Boolean {
            val cursorType = content.optString("cursor_type").ifEmpty { content.optString("cursorType") }
            val value = content.optString("value")
            val isCursor = type(content).endsWith("TimelineCursor") || cursorType.isNotEmpty() ||
                entryId?.startsWith("cursor-") == true
            if (!isCursor) return false
            if (cursorType.equals("Bottom", true) || entryId?.startsWith("cursor-bottom") == true) {
                if (value.isNotEmpty()) bottomCursor = value
            }
            return true
        }

        private fun visitItem(content: JSONObject) {
            val tweetResults = content.optJSONObject("tweet_results") ?: content.optJSONObject("tweetResult") ?: return
            visitTweet(tweetResults)
        }

        private fun visitTweet(tweetResults: JSONObject) {
            var result = tweetResults.optJSONObject("result") ?: tweetResults.optJSONObject("tweet") ?: return
            if (type(result) == "TweetWithVisibilityResults") result = result.optJSONObject("tweet") ?: return
            if (type(result) in setOf("TweetTombstone", "TweetUnavailable", "TweetPreviewDisplay")) return
            tweetCount++
            val tweetId = result.optString("rest_id").ifEmpty { result.optString("id_str").ifEmpty { result.optString("id") } }
            val legacy = result.optJSONObject("legacy")
            val mediaArray = result.optJSONArray("media_entities") ?: result.optJSONArray("media_entities2")
                ?: legacy?.optJSONObject("extended_entities")?.optJSONArray("media")
                ?: legacy?.optJSONObject("entities")?.optJSONArray("media")
            if (mediaArray == null) return
            for (i in 0 until mediaArray.length()) {
                val entity = mediaArray.optJSONObject(i) ?: continue
                addMedia(entity, tweetId)
            }
        }

        private fun addMedia(entity: JSONObject, tweetId: String) {
            val graphQl = entity.has("media_results") || entity.has("media_info")
            val media = resolveMedia(entity) ?: entity
            val info = media.optJSONObject("media_info")
            val infoType = type(info)
            val mediaIdRaw = media.optString("media_id").ifEmpty { media.optString("id_str") }
                .ifEmpty { entity.optString("id_str") }
            val availability = media.optJSONObject("media_availability_v2")
            val status = availability?.optString("status").orEmpty()
            if (status.isNotEmpty() && !status.equals("Available", true) && !status.equals("None", true)) {
                skipped++
                return
            }
            val allowDownload = media.optJSONObject("allow_download_status")?.optBoolean("allow_download", true) ?: true
            val resolved = when {
                graphQl && (infoType == "ApiImage" || info?.has("original_img_url") == true) ->
                    normalizePhoto(info?.optString("original_img_url").orEmpty()) to false
                graphQl -> bestVariantUrl(info, media) to true
                else -> legacyUrl(entity, infoType) to !entity.optString("type").equals("photo", true)
            }
            val url = resolved.first
            if (url == null) {
                skipped++
                return
            }
            val mediaId = mediaIdRaw.takeIf { mediaIdPattern.matches(it) } ?: hash(url)
            if (!seen.add(mediaId)) return
            items += ProfileMediaItem(
                tweetId = tweetId.takeIf { mediaIdPattern.matches(it) } ?: mediaId,
                mediaId = mediaId,
                type = if (resolved.second) "video" else "image",
                url = url,
                allowDownload = allowDownload,
                sensitive = false,
            )
        }

        private fun resolveMedia(entity: JSONObject): JSONObject? {
            val results = entity.optJSONObject("media_results") ?: return entity.takeIf { it.has("media_info") }
            return results.optJSONObject("result")
                ?: results.optJSONArray("result")?.optJSONObject(0)
                ?: results.optJSONObject("media_results")?.optJSONObject("result")
        }

        private fun bestVariantUrl(info: JSONObject?, media: JSONObject): String? {
            val variants = info?.optJSONArray("variants") ?: media.optJSONObject("video_info")?.optJSONArray("variants") ?: return null
            var best: String? = null
            var bestRate = -1L
            for (i in 0 until variants.length()) {
                val variant = variants.optJSONObject(i) ?: continue
                val url = variant.optString("url")
                val contentType = variant.optString("content_type").ifEmpty { variant.optString("contentType") }
                if (contentType.isNotEmpty() && !contentType.equals("video/mp4", true)) continue
                if (!validVideoUrl(url)) continue
                val rate = variant.optLong("bit_rate", variant.optLong("bitrate", -1L))
                if (best == null || rate > bestRate) { best = url; bestRate = rate }
            }
            return best
        }

        private fun legacyUrl(entity: JSONObject, infoType: String): String? {
            if (entity.optString("type").equals("photo", true)) {
                return normalizePhoto(entity.optString("media_url_https").ifEmpty { entity.optString("media_url") })
            }
            if (infoType == "ApiImage") return normalizePhoto(entity.optString("original_img_url"))
            return bestVariantUrl(entity.optJSONObject("video_info"), entity)
        }
    }

    private fun type(value: JSONObject?): String =
        value?.optString("__typename").orEmpty().ifEmpty { value?.optString("type").orEmpty() }

    private fun validVideoUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", true) && uri.host.equals("video.twimg.com", true) &&
            uri.rawUserInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443) &&
            uri.path.orEmpty().endsWith(".mp4", true)
    }.getOrDefault(false)

    internal fun normalizePhoto(raw: String): String? {
        if (raw.isEmpty()) return null
        return runCatching {
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && uri.host.equals("pbs.twimg.com", true) &&
            uri.rawUserInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443))
        require(uri.path.orEmpty().startsWith("/media/"))
        val extension = uri.path.substringAfterLast('.', "").substringBefore(':')
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        val format = query.firstOrNull { it.startsWith("format=") }?.substringAfter('=')
            ?: extension.takeIf { it in photoFormats } ?: "jpg"
        require(format in photoFormats)
        val path = if (extension in photoFormats) uri.path.substringBeforeLast('.') else uri.path
        "https://pbs.twimg.com$path?" + (query.filterNot {
            it.substringBefore('=') in setOf("format", "name")
        } + listOf("format=$format", "name=orig")).joinToString("&")
        }.getOrNull()
    }

    private fun hash(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)

}
