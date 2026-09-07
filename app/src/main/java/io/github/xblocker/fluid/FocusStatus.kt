package io.github.xblocker.fluid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    fun onDiagnostics(context: Context, json: org.json.JSONObject) {
        val blocked = json.optLong("blocked").coerceAtLeast(0)
        val tweets = maxOf(json.optLong("tweets"), blocked)
        val age = System.currentTimeMillis() - json.optLong("lastSeen")
        val fresh = age >= 0 && age < FluidStatus.REPORT_TIMEOUT_MS
        val inX = fresh && json.optBoolean("fg", false)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!inX) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val icon = runCatching { R.drawable.ic_notification }.getOrElse { android.R.drawable.stat_notify_error }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("XBlocker")
            .setContentText("本轮已拦截 $blocked / $tweets 条")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(FluidStatus.REPORT_TIMEOUT_MS - age)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        XiaomiFocusNotification.apply(context, builder, icon, blocked, tweets)
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
