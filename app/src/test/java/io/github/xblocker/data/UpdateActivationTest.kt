package io.github.xblocker.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateActivationTest {
    private val ready = UpdateActivationState("21:100:nonce", 21, "0.3.5", ActivationPhase.READY, previousPid = 122)
    private fun report(pid: Int = 123, version: Int = 21, hooks: Int = 1) = JSONObject().put("pid", pid).put("moduleCode", version).put("hooks", hooks)

    @Test fun scanSuccessDoesNotProveThatTheRunningHostLoadedTheNewModule() {
        assertTrue(ready.acceptsHost(report()))
        assertFalse(ready.acceptsHost(report(pid = 122)))
        assertFalse(ready.acceptsHost(report(pid = 0)))
        assertFalse(ready.acceptsHost(report(version = 20)))
        assertFalse(ready.acceptsHost(report(hooks = 0)))
        assertFalse(ready.acceptsHost(JSONObject().put("pid", 123).put("hooks", 1).put("installedModuleCode", 21)))
        assertFalse(ready.copy(phase = ActivationPhase.SCANNING).acceptsHost(report()))
    }
    @Test fun serializedResultsPreserveDismissalAndScanOrigin() {
        val source = ready.copy(dismissed = true, scan = JSONObject().put("mode", "cache"))
        val restored = UpdateActivationState.fromJson(JSONObject(source.toJson().toString()))
        assertEquals(source.token, restored.token)
        assertEquals(source.previousPid, restored.previousPid)
        assertEquals(source.phase, restored.phase)
        assertTrue(restored.dismissed)
        assertEquals("cache", restored.scan!!.getString("mode"))
    }
    @Test fun completedOrFailedResultsAreNotAutomaticRetryRequests() {
        assertTrue(ready.copy(phase = ActivationPhase.QUEUED).running)
        assertTrue(ready.copy(phase = ActivationPhase.SCANNING).running)
        for (phase in listOf(ActivationPhase.READY, ActivationPhase.ACTIVE, ActivationPhase.FAILED)) assertFalse(ready.copy(phase = phase).running)
    }
}
