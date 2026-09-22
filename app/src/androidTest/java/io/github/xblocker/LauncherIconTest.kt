package io.github.xblocker

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.data.LauncherIcon
import io.github.xblocker.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test fun hidingAndRestoringIconKeepsModuleSettingsAndNotificationTargetAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, "io.github.xblocker.ui.LauncherAlias")
        val main = ComponentName(context, MainActivity::class.java)
        val original = pm.getComponentEnabledSetting(alias)
        fun activities(category: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName), 0,
        ).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }

        try {
            LauncherIcon.setHidden(context, false)
            assertFalse(LauncherIcon.isHidden(context))
            assertEquals(listOf(alias), activities(Intent.CATEGORY_LAUNCHER))

            LauncherIcon.setHidden(context, true)
            assertTrue(LauncherIcon.isHidden(context))
            assertTrue(activities(Intent.CATEGORY_LAUNCHER).isEmpty())
            assertEquals(listOf(main), activities("de.robv.android.xposed.category.MODULE_SETTINGS"))
            assertEquals(main, pm.getLaunchIntentForPackage(context.packageName)?.component)
            assertTrue(pm.getActivityInfo(main, 0).enabled)
            assertTrue(pm.getActivityInfo(main, 0).exported)

            LauncherIcon.setHidden(context, false)
            assertFalse(LauncherIcon.isHidden(context))
            assertEquals(listOf(alias), activities(Intent.CATEGORY_LAUNCHER))
        } finally {
            pm.setComponentEnabledSetting(alias, original, PackageManager.DONT_KILL_APP)
        }
    }
}
