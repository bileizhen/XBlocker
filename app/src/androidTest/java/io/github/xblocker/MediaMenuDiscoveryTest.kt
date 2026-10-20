package io.github.xblocker

import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.hook.MediaMenuLocator
import io.github.xblocker.hook.MediaMenuTargets
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaMenuDiscoveryTest {
    @Test fun discoversMenusAndModelsWithoutVersionOrObfuscatedNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = InstrumentationRegistry.getArguments().getString("mediaApkPath")
        val result = if (path.isNullOrEmpty()) MediaMenuLocator.resolve(context, force = true)
            else MediaMenuLocator.inspectApk(context, path)
        assertNotNull(result.report.toString(), result.targets)
        val targets = result.targets!!
        assertTrue("No video menu: ${result.report}", targets.videoMenus.isNotEmpty())
        assertTrue("Sheet shape missing: ${result.report}", targets.sheetReady)
        assertNotNull(targets.models.imageClass)
        assertTrue(targets.models.videoClasses.isNotEmpty())
        assertTrue(targets.models.variantClasses.isNotEmpty())
        assertTrue("No photo path: ${result.report}", targets.photoPopup != null || targets.photoSheet != null || targets.legacyRows.isNotEmpty())
        assertEquals(targets, MediaMenuTargets.fromJson(targets.toJson()))
        File(context.filesDir, "media-discovery-${path?.substringAfterLast('/') ?: "installed"}.json")
            .writeText(result.report.put("targets", targets.toJson()).toString())
        if (path.isNullOrEmpty()) {
            val cached = MediaMenuLocator.resolve(context)
            assertEquals("cache", cached.report.getString("mode"))
            assertEquals(targets, cached.targets)
        }
    }
}
