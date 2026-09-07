package io.github.xblocker.fluid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import io.github.xblocker.R
import io.github.xblocker.ui.MainActivity

/**
 * Optional Xiaomi focus-notification route. It deliberately has its own
 * channel and notification ID so the native Super Island / Fluid Cloud route
 * never receives focus extras.
 */
object FocusStatus {
    const val NOTIFICATION_ID = 43
    private const val CHANNEL_ID = "focus_status"
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "焦点通知转换", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "将 XBlocker 实时拦截状态转换为小米焦点通知"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun onDiagnostics(context: Context, json: org.json.JSONObject): Boolean {
        val blocked = json.optLong("blocked").coerceAtLeast(0)
        val tweets = maxOf(json.optLong("tweets"), blocked)
        val age = System.currentTimeMillis() - json.optLong("lastSeen")
        val fresh = age >= 0 && age < FluidStatus.REPORT_TIMEOUT_MS
        val inX = fresh && json.optBoolean("fg", false)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!inX) {
            manager.cancel(NOTIFICATION_ID)
            return true
        }
        val icon = R.drawable.ic_notification
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(notificationIcon(context))
            .setContentTitle("XBlocker")
            .setContentText("本轮已拦截 $blocked / $tweets 条")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(FluidStatus.REPORT_TIMEOUT_MS - age)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent().setClassName(MODULE_PACKAGE, MainActivity::class.java.name), PendingIntent.FLAG_IMMUTABLE))
        XiaomiFocusNotification.apply(context, builder, icon, blocked, tweets)
        return runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }.isSuccess
    }

    private fun notificationIcon(context: Context): Icon = runCatching {
        val module = if (context.packageName == MODULE_PACKAGE) context
        else context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        Icon.createWithResource(module, R.drawable.ic_notification)
    }.getOrElse { Icon.createWithResource(context, android.R.drawable.stat_notify_error) }
}
