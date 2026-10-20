package io.github.xblocker

import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.hook.SspAdLocator
import io.github.xblocker.hook.SspAdTargets
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SspAdDiscoveryTest {
    @Test fun findsSspDisablePolicyAndSharedTimelineStateThenUsesMatchingCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = SspAdLocator.resolve(context, force = true)
        assertEquals(result.report.toString(), 3, result.report.optInt("success"))
        val targets = checkNotNull(result.targets)
        assertTrue(targets.policies.size >= 2)
        assertTrue(targets.gates.isNotEmpty())
        assertNotNull(targets.slotIdMethod)
        assertTrue(targets.stateConstructors.isNotEmpty())
        assertNotNull(targets.toImmutable)
        assertEquals(targets, SspAdTargets.fromJson(targets.toJson()))
        val cached = SspAdLocator.resolve(context)
        assertEquals("cache", cached.report.getString("mode"))
        assertEquals(targets, cached.targets)
        File(context.filesDir, "ssp-discovery.json").writeText(result.report.put("targets", targets.toJson()).toString())
    }
}
