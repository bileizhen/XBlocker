package io.github.xblocker.ui

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

    fun label(packageName: String): String = when (packageName) {
        "com.android.systemui" -> "系统界面（前台胶囊豁免 / 焦点通知）"
        "com.oplus.systemui.plugins" -> "O+ 系统界面插件（流体云）"
        "com.oplus.pantanal.ums" -> "Pantanal 服务（流体云）"
        "com.coloros.assistantscreen" -> "智慧助理（流体云）"
        else -> packageName
    }
}
