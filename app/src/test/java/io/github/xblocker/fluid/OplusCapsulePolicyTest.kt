package io.github.xblocker.fluid

import org.junit.Assert.*
import org.junit.Test

class OplusCapsulePolicyTest {
    @Test fun hostAndProviderStatusNotificationsStartCollapsed() {
        for (publisher in listOf("com.twitter.android", "io.github.bileizhen.xblocker")) {
            assertTrue(OplusCapsulePolicy.suppressAutomaticExpansion(publisher, 42, "fluid_status_promoted"))
        }
    }

    @Test fun otherNotificationsKeepTheirAutomaticPresentation() {
        assertFalse(OplusCapsulePolicy.suppressAutomaticExpansion("com.twitter.android", 7, "fluid_status_promoted"))
        assertFalse(OplusCapsulePolicy.suppressAutomaticExpansion("com.twitter.android", 42, "messages"))
        assertFalse(OplusCapsulePolicy.suppressAutomaticExpansion("com.other.app", 42, "fluid_status_promoted"))
        assertFalse(OplusCapsulePolicy.suppressAutomaticExpansion("io.github.bileizhen.xblocker", 43, "focus_status"))
        assertFalse(OplusCapsulePolicy.suppressAutomaticExpansion(null, 42, "fluid_status_promoted"))
        assertFalse(OplusCapsulePolicy.suppressAutomaticExpansion("com.twitter.android", 42, null))
    }
}
