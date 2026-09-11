package io.github.xblocker.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * OEM permission pages Android has no standard API for: autostart keeps the periodic cloud
 * sync alive, and the per-app live-activity switch (ColorOS 流体云 / MIUI focus notifications)
 * gates the blocking capsule. Launch attempts go in order — startActivity itself is the
 * only reliable test for exported/permission quirks — and always end at a standard page.
 */
object OemSettings {
    /** ColorOS 13+ hosts autostart inside the battery app; older builds use safecenter. */
    private val autostartCandidates = listOf(
        Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity")),
        Intent("com.oplus.battery.permission.startup.StartupAppListActivity").setPackage("com.oplus.battery"),
        Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
    )

    /** Only manufacturers known to gate background work behind an autostart switch. */
    fun applies(): Boolean = Build.MANUFACTURER.lowercase() in setOf(
        "oneplus", "oplus", "oppo", "realme", "xiaomi", "redmi", "huawei", "honor", "vivo", "iqoo", "meizu",
    )

    fun openAutostart(context: Context): Boolean = open(context, autostartCandidates + appDetails(context))

    /** ColorOS 15+ puts the per-app live-activity toggle in the app's notification settings. */
    fun openLiveActivity(context: Context): Boolean = open(context, listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context),
    ))

    private fun open(context: Context, intents: List<Intent>): Boolean = intents.any { intent ->
        runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
