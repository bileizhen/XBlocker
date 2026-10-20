package io.github.xblocker.hook

import org.junit.Assert.*
import org.junit.Test

class SspSlotPolicyTest {
    @Test fun ordinaryItemsAndDisabledFilteringRetainIdentity() {
        val items = listOf("a", "b")
        assertSame(items, SspSlotPolicy.remove(items, true) { it == "ad" })
        assertSame(items, SspSlotPolicy.remove(items, false) { true })
        assertFalse(SspSlotPolicy.enabled(false, true))
        assertFalse(SspSlotPolicy.enabled(true, false))
        assertTrue(SspSlotPolicy.enabled(true, true))
    }
    @Test fun removeConsecutiveAndAllAdsWithoutMutatingSourceOrReordering() {
        val items = listOf("ad", "a", "ad", "ad", "b", "ad")
        assertEquals(listOf("a", "b"), SspSlotPolicy.remove(items, true) { it == "ad" })
        assertEquals(6, items.size)
        assertTrue(SspSlotPolicy.remove(listOf("ad", "ad"), true) { true }.isEmpty())
    }
    @Test fun actualSlotDeduplicationIsBoundedAndDoesNotCountBlankIdentity() {
        val ledger = SspSlotLedger(2)
        assertFalse(ledger.record(""))
        assertTrue(ledger.record("a")); assertFalse(ledger.record("a"))
        assertTrue(ledger.record("b")); assertTrue(ledger.record("c"))
        assertFalse(ledger.record("c")); assertTrue(ledger.record("a"))
    }
}
