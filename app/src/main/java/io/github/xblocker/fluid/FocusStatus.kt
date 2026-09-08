package io.github.xblocker.fluid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
        val text = NotificationText.from(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, text.focusChannel, NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = text.focusDescription
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun onDiagnostics(context: Context, json: org.json.JSONObject): Boolean {
        val text = NotificationText.from(context)
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
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(notificationIcon(context))
            .setContentTitle("XBlocker")
            .setContentText(text.session.format(blocked, tweets))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(FluidStatus.REPORT_TIMEOUT_MS - age)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent().setClassName(MODULE_PACKAGE, MainActivity::class.java.name), PendingIntent.FLAG_IMMUTABLE))
        XiaomiFocusNotification.apply(context, builder, blocked, tweets)
        return runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }.isSuccess
    }

    private fun notificationIcon(context: Context): Icon = ModuleIcon.notification(context)
}
