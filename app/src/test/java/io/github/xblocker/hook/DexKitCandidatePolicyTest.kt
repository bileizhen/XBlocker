package io.github.xblocker.hook

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class DexKitCandidatePolicyTest {
    private val descriptor = "Lokhttp3/internal/connection/a;->b()Lokhttp3/Response;"

    @Test fun requiresAllBytecodeAndSignatureFeatures() {
        val calls = DexKitCandidatePolicy.requiredCalls.toList()
        assertTrue(DexKitCandidatePolicy.accepts(descriptor, Modifier.PUBLIC, listOf("Canceled"), calls))
        assertFalse(DexKitCandidatePolicy.accepts(descriptor, Modifier.PUBLIC, listOf("Canceled"), calls.drop(1)))
        assertFalse(DexKitCandidatePolicy.accepts(descriptor, Modifier.PUBLIC, listOf("canceled"), calls))
        for (flag in listOf(Modifier.STATIC, Modifier.ABSTRACT, Modifier.NATIVE)) {
            assertFalse(DexKitCandidatePolicy.accepts(descriptor, Modifier.PUBLIC or flag, listOf("Canceled"), calls))
        }
        assertFalse(DexKitCandidatePolicy.accepts(descriptor.replace("()", "(I)"), 1, listOf("Canceled"), calls))
        assertFalse(DexKitCandidatePolicy.validDescriptor(descriptor.replace("Lokhttp3/internal", "Levil/internal")))
        assertFalse(DexKitCandidatePolicy.validDescriptor(descriptor.replace("Response;", "Request;")))
    }

    @Test fun neverSelectsAnAmbiguousOrInvalidResult() {
        assertEquals(descriptor, DexKitCandidatePolicy.unique(listOf(descriptor)))
        assertNull(DexKitCandidatePolicy.unique(emptyList()))
        assertNull(DexKitCandidatePolicy.unique(listOf(descriptor, descriptor)))
        assertNull(DexKitCandidatePolicy.unique(listOf("not-a-method")))
    }

    @Test fun reportRoundTripsWithoutLosingWhetherScanningWasPerformed() {
        for (mode in listOf("fast", "cache", "dexkit", "failed")) {
            val report = DexKitScanReport("12.34.0", 100, "0.3.4", 20, mode,
                if (mode == "failed") 0 else 1, details = "test", targetDescriptor = descriptor,
                durationMs = 35, hostFingerprint = "abc", previousTargetDescriptor = descriptor,
                cacheChanged = if (mode == "cache") false else null)
            assertEquals(report, DexKitScanReport.fromJson(JSONObject(report.toJson().toString())))
        }
    }
}
