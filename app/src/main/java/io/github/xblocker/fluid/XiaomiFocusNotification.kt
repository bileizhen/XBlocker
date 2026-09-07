package io.github.xblocker.fluid

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.util.Log

internal object XiaomiFocusNotification {
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
    fun apply(context: Context, builder: Notification.Builder, icon: Int, blocked: Long, tweets: Long) {
        // Capability comes from SystemUI, independently of the Android API level / device brand.
        // Let SystemUI enforce focus permission and fall back to our normal notification.
        // Its canShowFocus provider call is slow and must not run on every host report.
        runCatching {
            val version = Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
            val params = XiaomiFocusPayload.create(version, blocked, tweets) ?: return
            val extras = Bundle().apply {
                putString(XiaomiFocusPayload.PARAM_KEY, params.toString())
                if (version >= 3) {
                    putBundle(XiaomiFocusPayload.PICS_KEY, Bundle().apply {
                        val module = if (context.packageName == MODULE_PACKAGE) context
                        else context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                        putParcelable(XiaomiFocusPayload.ICON_KEY, Icon.createWithResource(module, icon))
                    })
                }
            }
            builder.addExtras(extras)
        }.onFailure { Log.w("XBlocker.Fluid", "Xiaomi focus extras unavailable", it) }
    }
}
