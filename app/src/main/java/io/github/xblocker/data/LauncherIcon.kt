package io.github.xblocker.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** PackageManager persists the launcher state; no separate preference can drift out of sync. */
object LauncherIcon {
    private fun component(context: Context) =
        ComponentName(context.packageName, "io.github.xblocker.ui.LauncherAlias")

    fun isHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }
}
