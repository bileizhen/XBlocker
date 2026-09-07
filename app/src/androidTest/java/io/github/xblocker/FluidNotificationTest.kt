package io.github.xblocker

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.fluid.FluidStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual system notification storage/expiry without starting any service. */
@RunWith(AndroidJUnit4::class)
class FluidNotificationTest {
    @Test fun reportUpdatesAndSystemExpiresNotificationWithoutMonitorService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        assertFalse(services.any { it.name.endsWith(".FluidCloudService") })
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
        fun report(blocked: Long, fg: Boolean = true, age: Long = 0) =
            FluidStatus.onDiagnostics(context, JSONObject().put("blocked", blocked).put("tweets", 50)
                .put("fg", fg).put("lastSeen", System.currentTimeMillis() - age))
        fun active() = manager.activeNotifications.firstOrNull { it.id == FluidStatus.CAPSULE_ID }
        fun awaitState(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        try {
            FluidStatus.ensureChannels(context)
            report(2)
            awaitState("Report must publish without a service") { active() != null }
            assertTrue(active()!!.notification.timeoutAfter in 1..FluidStatus.REPORT_TIMEOUT_MS)
            report(7)
            awaitState("Subsequent report must update the existing notification") {
                active()?.notification?.extras?.getCharSequence("android.text")?.contains("7 / 50") == true
            }
            assertEquals(1, manager.activeNotifications.count { it.id == FluidStatus.CAPSULE_ID })
            report(7, fg = false)
            awaitState("Leaving X must cancel") { active() == null }
            // An already-aging report must get only its remaining lifetime, not another 12 seconds.
            report(8, age = FluidStatus.REPORT_TIMEOUT_MS - 2_000)
            awaitState("Returning to X must publish again") { active() != null }
            awaitState("Android must expire the notification without another report or service tick") { active() == null }
            report(8, age = FluidStatus.REPORT_TIMEOUT_MS + 1_000)
            SystemClock.sleep(200)
            assertNull(active())
        } finally {
            manager.cancel(FluidStatus.CAPSULE_ID)
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
