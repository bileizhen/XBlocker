package io.github.xblocker.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoryPresentationTest {
    private fun event(id: String = "1", time: Long = 1000, handle: String = "Alice", category: String = "自定义",
        reason: String = "正文", rule: String = "keyword") = JSONObject()
        .put("id", id).put("time", time).put("handle", handle).put("category", category).put("reason", reason).put("rule", rule)

    @Test fun rulesOnlyOfferAllowlistingForActualAccounts() {
        val records = historyRecords(listOf(event(), event("2", category = "推广", reason = "推广广告"),
            event("3", category = "转帖", reason = "时间线转帖"), event("4", handle = "")))
        assertEquals(listOf(HistoryFilter.Rules, HistoryFilter.Promoted, HistoryFilter.Reposts, HistoryFilter.Rules), records.map { it.kind })
        assertEquals(listOf(true, false, false, false), records.map { it.canAllowlist })
        assertEquals("alice", records.first().normalizedHandle)
    }

    @Test fun searchesAccountsAndRulesAsLiteralCaseInsensitiveText() {
        val records = historyRecords(listOf(event(rule = "/Foo\\.Bar/i"), event("2", handle = "Bob", rule = "示例词")))
        assertEquals("1", records.single { it.matches(" @ALICE ") }.id)
        assertEquals("1", records.single { it.matches("foo\\.bar") }.id)
        assertEquals("2", records.single { it.matches("示例") }.id)
        assertTrue(records.all { it.matches("  ") })
        assertTrue(records.none { it.matches("[.*") })
    }

    @Test fun videoAdSlotsHaveTheirOwnFilterAndNeverOfferAccountAllowlisting() {
        val records = historyRecords(listOf(
            event("ssp-slot", category = "视频广告", reason = "视频广告", rule = "SSP:slot-1"),
            event("reason-only", category = "", reason = "视频广告", handle = ""),
            event("promoted", category = "推广", reason = "推广广告"),
            event("rule", rule = "SSP:example"),
        ))
        assertEquals(listOf(HistoryFilter.VideoAds, HistoryFilter.VideoAds, HistoryFilter.Promoted,
            HistoryFilter.Rules), records.map { it.kind })
        assertEquals(listOf(false, false, false, true), records.map { it.canAllowlist })
        assertEquals("ssp-slot", records.single { it.matches("SSP:slot-1") }.id)
    }

    @Test fun refreshedEventsRetainKeysWhenNewRecordsArrive() {
        val before = historyRecords(listOf(event(), event("2", time = 2000)))
        val after = historyRecords(listOf(event("3", time = 3000), event("2", time = 2000), event()))
        assertEquals(listOf("3", "2", "1"), after.map { it.id })
        for (record in before) assertEquals(record.key, after.single { it.id == record.id }.key)
        val duplicates = historyRecords(listOf(event(), event(), JSONObject(), JSONObject()))
        assertEquals(duplicates.size, duplicates.map { it.key }.toSet().size)
    }

    @Test fun allOneThousandRecordsRemainSearchableIncludingTheOldest() {
        val records = historyRecords(List(1000) { index -> event("$index", time = index + 1L,
            rule = if (index == 0) "oldest-saved-rule" else "rule-$index") })
        assertEquals(1000, records.size)
        assertEquals("0", records.last().id)
        assertEquals("0", records.single { it.matches("oldest-saved-rule") }.id)
        assertEquals(1000, historyDays(records, ZoneId.of("UTC")).values.sumOf { it.size })
    }

    @Test fun dateGroupsFollowLocalMidnightAndKeepUnknownTimeSeparate() {
        val records = historyRecords(listOf(event(time = Instant.parse("2026-10-15T16:01:00Z").toEpochMilli()),
            event("2", time = Instant.parse("2026-10-15T15:59:00Z").toEpochMilli()), event("3", time = 0)))
        assertEquals(listOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 15), null),
            historyDays(records, ZoneId.of("Asia/Shanghai")).keys.toList())
        assertEquals(2, historyDays(records, ZoneId.of("UTC")).size)
    }
}
