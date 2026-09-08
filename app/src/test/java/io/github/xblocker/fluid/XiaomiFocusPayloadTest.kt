package io.github.xblocker.fluid

import org.junit.Assert.*
import org.junit.Test

class XiaomiFocusPayloadTest {
    @Test fun translatedTextIsInjectedWithoutChangingProtocolOrCounts() {
        val text = NotificationText(
            short = "已攔 %1\$s", total = "XBlocker · 已攔截 %1\$s 條",
            fraction = "已攔截 %1\$s / %2\$s 條", percent = "本輪攔截 %1\$s%%", label = "已攔",
        )
        val params = XiaomiFocusPayload.create(3, 12, 40, text)!!.getJSONObject("param_v2")
        assertEquals("已攔 12", params.getString("ticker"))
        assertEquals("已攔截 12 / 40 條", params.getJSONObject("baseInfo").getString("title"))
        assertEquals("本輪攔截 30%", params.getJSONObject("baseInfo").getString("content"))
        assertEquals(30, params.getJSONObject("progressInfo").getInt("progress"))
        assertEquals(XiaomiFocusPayload.BUSINESS, params.getString("business"))
    }

    @Test fun unsupportedSystemsKeepTheOriginalNotification() {
        for (version in listOf(-1, 0, 1)) {
            assertNull(XiaomiFocusPayload.create(version, 12, 40))
        }
    }

    @Test fun os2GetsTheSharedCardAndTickerWithoutIslandFields() {
        val params = XiaomiFocusPayload.create(2, 12, 40)!!.getJSONObject("param_v2")
        assertFalse(params.has("param_island"))
        assertEquals("Blocked 12", params.getString("ticker"))
        assertEquals("Blocked 12 / 40", params.getJSONObject("baseInfo").getString("title"))
        assertEquals(30, params.getJSONObject("progressInfo").getInt("progress"))
    }

    @Test fun os3AndLaterHaveValidSummaryRegionsAndCanReopenAfterCancel() {
        for (version in listOf(3, 4)) {
            val params = XiaomiFocusPayload.create(version, 1234, 5000)!!.getJSONObject("param_v2")
            assertTrue(params.getBoolean("updatable"))
            assertEquals("reopen", params.getString("reopen"))
            assertFalse(params.getBoolean("islandFirstFloat"))
            assertFalse(params.getBoolean("enableFloat"))
            assertFalse(params.getBoolean("filterWhenNoPermission"))
            val island = params.getJSONObject("param_island")
            val big = island.getJSONObject("bigIslandArea")
            assertEquals("1.2k", big.getJSONObject("textInfo").getString("title"))
            assertEquals("Blocked", big.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title"))
            for (pic in listOf(big.getJSONObject("imageTextInfoLeft").getJSONObject("picInfo"),
                island.getJSONObject("smallIslandArea").getJSONObject("picInfo"))) {
                assertEquals(XiaomiFocusPayload.ICON_KEY, pic.getString("pic"))
                assertEquals(1, pic.getInt("type"))
            }
            assertEquals("Blocked 1234 / 5000", params.getJSONObject("baseInfo").getString("title"))
        }
    }

    @Test fun emptyMalformedAndHugeCountersStayWithinProtocolLimits() {
        for ((blocked, total, expected) in listOf(
            Triple(0L, 0L, 0), Triple(-20L, -1L, 0), Triple(20L, 5L, 100),
            Triple(Long.MAX_VALUE, Long.MAX_VALUE, 100), Triple(1L, Long.MAX_VALUE, 0),
        )) {
            val payload = XiaomiFocusPayload.create(3, blocked, total)!!
            assertEquals(expected, payload.getJSONObject("param_v2").getJSONObject("progressInfo").getInt("progress"))
            assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size <= 3072)
        }
        assertEquals("Blocked 0 / 0", XiaomiFocusPayload.create(3, -2, -1)!!
            .getJSONObject("param_v2").getJSONObject("baseInfo").getString("title"))
    }

    @Test fun summaryCountsRemainShortAtEachUnitBoundary() {
        val cases = mapOf(0L to "0", 999L to "999", 1000L to "1.0k", 9999L to "9.9k",
            10000L to "10k", 999999L to "999k", 1000000L to "1M+", Long.MAX_VALUE to "1M+")
        for ((count, expected) in cases) assertEquals(expected, XiaomiFocusPayload.compactCount(count))
    }

    @Test fun ownershipMatchesOnlyThisModuleBusinessPayload() {
        assertTrue(XiaomiFocusPayload.owns(XiaomiFocusPayload.create(2, 3, 9)!!.toString()))
        assertTrue(XiaomiFocusPayload.owns(XiaomiFocusPayload.create(3, 3, 9)!!.toString()))
        // Foreign apps and every malformed shape must stay unrecognized.
        assertFalse(XiaomiFocusPayload.owns("""{"param_v2":{"business":"com.other.app"}}"""))
        assertFalse(XiaomiFocusPayload.owns("""{"param_v2":{}}"""))
        assertFalse(XiaomiFocusPayload.owns("""{"business":"xblocker_filter"}"""))
        assertFalse(XiaomiFocusPayload.owns("not json"))
        assertFalse(XiaomiFocusPayload.owns(""))
        assertFalse(XiaomiFocusPayload.owns(null))
    }
}
