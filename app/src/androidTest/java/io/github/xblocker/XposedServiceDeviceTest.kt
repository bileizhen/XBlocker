package io.github.xblocker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.data.XposedServiceClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in integration test: run separately on a device with this module enabled in LSPosed. */
@RunWith(AndroidJUnit4::class)
class XposedServiceDeviceTest {
    @Test fun enabledModuleReceivesRealFrameworkService() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("requireXposed") == "true")
        val connected = CountDownLatch(1)
        XposedServiceClient.onConnected { connected.countDown() }
        assertTrue("LSPosed did not deliver its service binder", connected.await(10, TimeUnit.SECONDS))
        assertNotNull("The real framework must answer getScope", XposedServiceClient.scope())
    }
}
