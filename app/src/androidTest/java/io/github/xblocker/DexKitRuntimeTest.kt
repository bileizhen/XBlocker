package io.github.xblocker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.hook.DexKitLocator
import io.github.xblocker.hook.DexKitScanReport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DexKitRuntimeTest {
    /** Reads the installed host APK; never launches or stops the user's X session. */
    @Test fun scansInstalledHostThenUsesOnlyMatchingCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scan = DexKitLocator.scan(context, force = true)
        assertEquals(scan.details, 1, scan.success)
        assertEquals("dexkit", scan.mode)
        assertTrue(scan.targetDescriptor.endsWith("()Lokhttp3/Response;"))
        assertTrue(scan.hostFingerprint.isNotEmpty())
        assertEquals(BuildConfig.VERSION_CODE, scan.moduleCode)
        assertEquals(scan, DexKitScanReport.fromJson(JSONObject(scan.toJson().toString())))
        val cache = DexKitLocator.scan(context)
        assertEquals("cache", cache.mode)
        assertEquals(scan.targetDescriptor, cache.targetDescriptor)
        assertEquals(scan.hostFingerprint, cache.hostFingerprint)
    }
}
