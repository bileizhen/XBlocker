package io.github.xblocker.fluid

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log

internal object XiaomiFocusNotification {
    fun apply(context: Context, builder: Notification.Builder, blocked: Long, tweets: Long) {
        // Capability comes from SystemUI, independently of the Android API level / device brand.
        // Let SystemUI enforce focus permission and fall back to our normal notification.
        // Its canShowFocus provider call is slow and must not run on every host report.
        runCatching {
            val version = Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
            val params = XiaomiFocusPayload.create(version, blocked, tweets, NotificationText.from(context)) ?: return
            val extras = Bundle().apply {
                putString(XiaomiFocusPayload.PARAM_KEY, params.toString())
                if (version >= 3) {
                    putBundle(XiaomiFocusPayload.PICS_KEY, Bundle().apply {
                        putParcelable(XiaomiFocusPayload.ICON_KEY, ModuleIcon.notification(context))
                    })
                }
            }
            builder.addExtras(extras)
        }.onFailure { Log.w("XBlocker.Fluid", "Xiaomi focus extras unavailable", it) }
    }
}
