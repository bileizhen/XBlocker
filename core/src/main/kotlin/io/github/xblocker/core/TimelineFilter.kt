package io.github.xblocker.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class BlockEvent(val id: String, val handle: String, val reason: String, val rule: String, val category: String)
data class FilterResult(val json: String, val events: List<BlockEvent>, val recognized: Boolean)

/** Content-free pipeline counters so a device session shows exactly where filtering stops. */
class FilterStats {
    val responses = java.util.concurrent.atomic.AtomicLong()
    val envelopes = java.util.concurrent.atomic.AtomicLong()
    val jsonParsed = java.util.concurrent.atomic.AtomicLong()
    val entriesArrays = java.util.concurrent.atomic.AtomicLong()
    val entriesInspected = java.util.concurrent.atomic.AtomicLong()
    val tweets = java.util.concurrent.atomic.AtomicLong()
    val texts = java.util.concurrent.atomic.AtomicLong()
    val replies = java.util.concurrent.atomic.AtomicLong()
    val promoted = java.util.concurrent.atomic.AtomicLong()
    val reposts = java.util.concurrent.atomic.AtomicLong()
    val blocked = java.util.concurrent.atomic.AtomicLong()
    /** Field-name paths of the first recognized payloads; schema only, never values. */
    val fingerprint: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fingerprintsTaken = AtomicInteger()
    fun takeFingerprint(entries: JSONArray) {
        if (fingerprintsTaken.getAndIncrement() >= 6) return
        for (i in 0 until minOf(entries.length(), 3)) fingerprint(entries.optJSONObject(i), "", 0)
    }
    private fun fingerprint(node: Any?, path: String, depth: Int) {
        if (depth > 9 || fingerprint.size >= 200) return
        when (node) {
            is JSONObject -> for (key in node.keys().asSequence().toList()) {
                val child = "$path.$key".removePrefix(".")
                if (INTERESTING.containsMatchIn(key)) fingerprint.add(child)
                fingerprint(node.opt(key), child, depth + 1)
            }
            is JSONArray -> fingerprint(node.opt(0), "$path[]", depth + 1)
        }
    }
    fun toMap(): Map<String, Long> = mapOf(
        "responses" to responses.get(), "envelopes" to envelopes.get(), "jsonParsed" to jsonParsed.get(),
        "entriesArrays" to entriesArrays.get(), "entriesInspected" to entriesInspected.get(), "tweets" to tweets.get(),
        "texts" to texts.get(), "replies" to replies.get(), "promoted" to promoted.get(),
        "reposts" to reposts.get(), "blocked" to blocked.get())
    companion object {
        private val INTERESTING = Regex("(?i)(tweet|entries|entry|content|item|module|result|legacy|full_text|user|core|promoted|typename|cursor|reply_to|conversation|rest_id|social|context)")
    }
}

/** Mutate only timeline entries/items. Never walk into a quoted status, user or media. */
class TimelineFilter(private val engine: RuleEngine, val stats: FilterStats = FilterStats()) {
    val ruleCount: Int get() = engine.count
    val rejectedCount: Int get() = engine.rejected.size
    fun filter(input: String, operation: String? = null): FilterResult {
        stats.responses.incrementAndGet()
        if (!engine.settings.enabled || input.length > MAX_CHARS || !input.contains("\"entries\"") &&
            !input.contains("\"moduleItems\"")) return FilterResult(input, emptyList(), false)
        stats.envelopes.incrementAndGet()
        return try {
            val root = JSONObject(input)
            stats.jsonParsed.incrementAndGet()
            val events = mutableListOf<BlockEvent>()
            var recognized = false
            fun visit(node: Any?, depth: Int, inProfile: Boolean) {
                if (depth > 40) return
                when (node) {
                    is JSONObject -> {
                        for (key in node.keys().asSequence().toList()) {
                            val value = node.opt(key)
                            // Profile tabs use the same timeline_response/entries shape as Home.
                            // Keep this scope local to the branch, including paginated module items.
                            val profile = inProfile || key in PROFILE_KEYS
                            if (value is JSONArray && key in setOf("entries", "moduleItems")) {
                                stats.entriesArrays.incrementAndGet()
                                stats.takeFingerprint(value)
                                if ((0 until value.length()).any { i -> value.optJSONObject(i)?.let { it.has("entryId") || it.has("entry_id") } == true }) {
                                    recognized = true
                                }
                                filterEntries(value, events, !profile || !engine.settings.preserveProfileReposts)
                            } else if (key !in setOf("quoted_status_result", "retweeted_status_result", "user_results", "tweet_results")) {
                                visit(value, depth + 1, profile)
                            }
                        }
                    }
                    is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1, inProfile)
                }
            }
            visit(root, 0, operation?.startsWith("User") == true || operation?.startsWith("Profile") == true ||
                operation == "ImmersiveViewerProfileMixerTimeline")
            FilterResult(if (events.isEmpty()) input else root.toString(), events, recognized)
        } catch (_: Exception) { FilterResult(input, emptyList(), false) }
    }

    private fun filterEntries(entries: JSONArray, events: MutableList<BlockEvent>, allowReposts: Boolean) {
        for (i in entries.length() - 1 downTo 0) {
            val entry = entries.optJSONObject(i) ?: continue
            stats.entriesInspected.incrementAndGet()
            val content = entry.optJSONObject("content") ?: entry.optJSONObject("item") ?: entry
            val items = content.optJSONArray("items")
            if (items != null) {
                val originalCount = items.length()
                filterEntries(items, events, allowReposts)
                if (originalCount > 0 && items.length() == 0) entries.remove(i)
                continue
            }
            val item = content.optJSONObject("itemContent") ?: content
            // Cursors and non-tweet recommendations must remain intact.
            val entryId = entry.optString("entryId").ifBlank { entry.optString("entry_id") }
            if (item.has("cursorType") || item.has("cursor_type") || entryId.startsWith("cursor-")) continue
            val container = findTweetContainer(item, 0)
            val result = container?.let { it.optJSONObject("tweetResult") ?: it.optJSONObject("tweet_results") }?.optJSONObject("result")
            val tweet = result?.let(::readTweet)
            if (tweet != null) {
                stats.tweets.incrementAndGet()
                if (tweet.text.isNotBlank()) stats.texts.incrementAndGet()
                if (tweet.isReply) stats.replies.incrementAndGet()
            }
            val promoted = container?.optJSONObject("promotedMetadata") != null || container?.optJSONObject("tweetPromotedMetadata") != null ||
                container?.optJSONObject("promoted_metadata") != null ||
                item.optJSONObject("promotedMetadata") != null || content.optJSONObject("promotedMetadata") != null ||
                item.optJSONObject("promoted_metadata") != null ||
                result?.optJSONObject("promoted_content") != null || result?.optJSONObject("legacy")?.optJSONObject("promoted_content") != null
            if (promoted) stats.promoted.incrementAndGet()
            val repostSignal = if (container != null && tweet != null) repostSignal(result, container, item, content) else null
            if (repostSignal != null) stats.reposts.incrementAndGet()
            val hit = when {
                engine.settings.blockPromoted && promoted -> Match("推广广告", "promotedMetadata", "推广")
                engine.settings.blockReposts && allowReposts && repostSignal != null -> Match("时间线转帖", repostSignal, "转帖")
                else -> tweet?.let(engine::match)
            }
            if (hit != null) {
                stats.blocked.incrementAndGet()
                entries.remove(i)
                events += BlockEvent(tweet?.id?.takeIf { it.isNotBlank() } ?: entryId, tweet?.handle.orEmpty(), hit.reason, hit.rule, hit.category)
            }
        }
    }

    /** Only inspect the outer tweet and its timeline context, never quoted/embedded tweets.
     * `retweeted` is the viewer's action state and `retweet_count` is an engagement count;
     * neither says that this timeline entry is a repost. Context text is localized/user-controlled.
     */
    private fun repostSignal(result: JSONObject?, vararg contexts: JSONObject): String? {
        for (context in contexts) {
            for (key in listOf("socialContext", "social_context")) {
                val social = context.optJSONObject(key) ?: continue
                val general = social.optJSONObject("generalContext") ?: social.optJSONObject("general_context") ?: social
                if (general.optString("contextType").equals("Retweet", ignoreCase = true) ||
                    general.optString("context_type").equals("Retweet", ignoreCase = true)) return "$key.Retweet"
            }
        }
        val tweet = result?.let(::unwrapTweet) ?: return null
        for (body in listOfNotNull(tweet.optJSONObject("legacy"), tweet.optJSONObject("details"), tweet)) {
            // X 12.25 uses legacy.repostedStatusResults, without a social context label.
            for (key in listOf("retweeted_status_result", "repostedStatusResults")) {
                val original = body.optJSONObject(key)?.optJSONObject("result")
                if (original != null && original.length() > 0) return key
            }
            val legacyOriginal = body.optJSONObject("retweeted_status")
            if (legacyOriginal != null && legacyOriginal.length() > 0) return "retweeted_status"
        }
        return null
    }

    /**
     * The web payload nests a tweet at itemContent.tweet_results.result, the Android URT
     * payload at itemContent.content.tweetResult.result and thread replies under
     * conversationTweetComponent.tweet.tweetResult.result. Search shallowly for the object
     * holding the tweet reference instead of hard-coding one shape, stopping at the first
     * match so quoted tweets stay untouched.
     */
    private fun findTweetContainer(node: Any?, depth: Int): JSONObject? {
        if (depth > 4) return null
        when (node) {
            is JSONObject -> {
                if (node.optJSONObject("tweetResult") != null || node.optJSONObject("tweet_results") != null) return node
                for (key in node.keys().asSequence().toList()) {
                    if (key in setOf("quoted_status_result", "retweeted_status_result", "user_results", "user_result")) continue
                    findTweetContainer(node.opt(key), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until minOf(node.length(), 40)) findTweetContainer(node.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun readTweet(result: JSONObject): Tweet? {
        val tweet = unwrapTweet(result) ?: return null
        // 12.16.3's new client moves text/reply metadata to details while retaining legacy.
        val bodies = listOfNotNull(tweet.optJSONObject("details"), tweet.optJSONObject("legacy"), tweet)
        fun statusField(key: String): String = bodies.asSequence().mapNotNull { it.opt(key) }
            .filter { it != JSONObject.NULL }.map { it.toString() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val text = tweet.optJSONObject("note_tweet")?.optJSONObject("note_tweet_results")?.optJSONObject("result")?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: statusField("full_text").ifBlank { statusField("text") }
        // The Android payload names the link "user_result", the web payload "user_results".
        val user = tweet.optJSONObject("core")?.let { it.optJSONObject("user_results") ?: it.optJSONObject("user_result") }?.optJSONObject("result")
        val userLegacy = user?.optJSONObject("legacy")
        val userCore = user?.optJSONObject("core")
        val replyId = statusField("in_reply_to_status_id_str")
        val replyReference = tweet.optJSONObject("reply_to_results")?.opt("rest_id")
            ?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
        val id = statusField("rest_id").ifBlank { statusField("id_str").ifBlank { statusField("id") } }
        val explicitReply = listOf(replyId, replyReference).any { it.isNotBlank() && it != "0" }
        // Thread roots carry conversation_id_str equal to their own id; replies carry the root's.
        val conversationId = statusField("conversation_id_str")
        val threadReply = conversationId.isNotBlank() && id.isNotBlank() && conversationId != id
        return Tweet(id, text,
            userCore?.optString("name")?.takeIf { it.isNotEmpty() } ?: userLegacy?.optString("name").orEmpty(),
            userCore?.optString("screen_name")?.takeIf { it.isNotEmpty() } ?: userLegacy?.optString("screen_name").orEmpty(),
            explicitReply || threadReply)
    }
    private fun unwrapTweet(result: JSONObject): JSONObject? =
        if (result.optString("__typename") == "TweetWithVisibilityResults") result.optJSONObject("tweet") else result

    companion object {
        const val MAX_CHARS = 8 * 1024 * 1024
        // Parser fallbacks have no request URL; profile timelines are nested under a user.
        private val PROFILE_KEYS = setOf("user", "user_result", "user_results", "profile_timeline", "profile_timeline_v2")
    }
}
