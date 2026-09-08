package io.github.xblocker.ui

import io.github.xblocker.R

import android.os.Build
import java.util.Locale

/** Which system-side scopes the native capsule route needs on the current device. */
object ScopeNotice {
    fun requiredScopes(): List<String> {
        val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase(Locale.ROOT)
        val oplus = listOf("oneplus", "oppo", "realme", "oplus").any { brand.contains(it) }
        return buildList {
            add("com.android.systemui")
            if (oplus) {
                add("com.oplus.systemui.plugins")
                add("com.oplus.pantanal.ums")
                add("com.coloros.assistantscreen")
            }
        }
    }

    fun label(context: android.content.Context, packageName: String): String = when (packageName) {
        "com.android.systemui" -> context.getString(R.string.system_ui_foreground_capsule_exemption_focus_notifications)
        "com.oplus.systemui.plugins" -> context.getString(R.string.o_system_ui_plugins_fluid_cloud)
        "com.oplus.pantanal.ums" -> context.getString(R.string.pantanal_service_fluid_cloud)
        "com.coloros.assistantscreen" -> context.getString(R.string.smart_assistant_fluid_cloud)
        else -> packageName
    }
}
