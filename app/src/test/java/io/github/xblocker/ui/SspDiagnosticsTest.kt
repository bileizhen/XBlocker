package io.github.xblocker.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SspDiagnosticsTest {
    @Test fun countsOnlyUniqueRemovedSlotsAndNeverPolicyCallsOrDiscoveredSlots() {
        val presentation = sspDiagnostics(JSONObject().put("sspAds", "active:3/cache")
            .put("sspBlocked", 2).put("sspPolicyCalls", 500).put("sspSlots", 80))
        assertEquals(2L, presentation.blockedSlots)
        assertEquals(SspAdapterStatus.Active, presentation.status)
        assertEquals(0L, sspDiagnostics(JSONObject().put("sspPolicyCalls", 500)).blockedSlots)
        assertEquals(0L, sspDiagnostics(JSONObject().put("sspBlocked", -1)).blockedSlots)
    }

    @Test fun distinguishesPartialDiscoveryFromInstalledHooksAndMissingReports() {
        for ((raw, expected) in listOf("scanning" to SspAdapterStatus.Scanning,
            "active:3/dexkit" to SspAdapterStatus.Active, "partial:2/cache" to SspAdapterStatus.Partial,
            "scan_failed" to SspAdapterStatus.Failed, "" to SspAdapterStatus.Waiting,
            "future_status" to SspAdapterStatus.Waiting)) {
            assertEquals(raw, expected, sspDiagnostics(JSONObject().put("sspAds", raw)).status)
        }
        val presentation = sspDiagnostics(JSONObject().put("sspScan", JSONObject().put("mode", "cache")
            .put("success", 2).put("total", 3).put("items", JSONObject().put("policy", true)
                .put("gate", true).put("slots", false))))
        assertEquals("cache", presentation.scanMode)
        assertEquals(mapOf("policy" to true, "gate" to true, "slots" to false), presentation.capabilities)
        assertTrue(sspDiagnostics(JSONObject()).capabilities.isEmpty())
    }
}
