package io.github.xblocker.ui

import io.github.xblocker.core.RuleParser
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal enum class HistoryFilter { All, Rules, Promoted, VideoAds, Reposts }

internal data class HistoryRecord(
    val key: String,
    val id: String,
    val handle: String,
    val reason: String,
    val rule: String,
    val category: String,
    val time: Long,
) {
    val kind: HistoryFilter get() = when {
        category == "视频广告" || reason == "视频广告" -> HistoryFilter.VideoAds
        category == "推广" || reason == "推广广告" -> HistoryFilter.Promoted
        category == "转帖" || reason == "时间线转帖" -> HistoryFilter.Reposts
        else -> HistoryFilter.Rules
    }
    val canAllowlist: Boolean get() = kind == HistoryFilter.Rules && handle.isNotBlank()
    val normalizedHandle: String get() = RuleParser.handle(handle)
    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase(Locale.ROOT)
        return needle.isEmpty() || handle.lowercase(Locale.ROOT).contains(needle.removePrefix("@")) ||
            rule.lowercase(Locale.ROOT).contains(needle)
    }
}

/** Immutable values and stable keys keep expanded rows attached to the same event during refresh. */
internal fun historyRecords(events: List<JSONObject>): List<HistoryRecord> {
    val occurrences = mutableMapOf<String, Int>()
    return events.map { event ->
        val id = event.optString("id")
        val time = event.optLong("time")
        val identity = "$id:$time"
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        HistoryRecord("$identity:$occurrence", id, event.optString("handle").trim().removePrefix("@"),
            event.optString("reason"), event.optString("rule"), event.optString("category"), time)
    }.sortedByDescending { it.time }
}

internal fun historyDays(records: List<HistoryRecord>, zone: ZoneId): Map<LocalDate?, List<HistoryRecord>> =
    records.groupBy { if (it.time <= 0) null else Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
