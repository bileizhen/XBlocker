package io.github.xblocker.fluid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.github.xblocker.R
import io.github.xblocker.ui.MainActivity

/**
 * Posts/cancels the fluid-cloud capsule notification from whatever context just received
 * diagnostics. X's bridge reports arrive as binder calls into this process, so the system
 * guarantees CPU here even when OEM throttling suspends our polling loops.
 */
object FluidStatus {
    const val CAPSULE_ID = 42
    private const val CAPSULE_CHANNEL = "fluid_status_promoted"
    private const val SPAM_COLOR = 0xFFE5484D.toInt()
    private const val KEPT_COLOR = 0xFF46A759.toInt()

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CAPSULE_CHANNEL, "实时拦截状态", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "使用 X 时在超级岛、流体云或通知栏显示本轮拦截进度"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    /** diagnostics carries "fg", counters and "lastSeen" as persisted by Repository.report. */
    fun onDiagnostics(context: Context, json: org.json.JSONObject) {
        val blocked = json.optLong("blocked").coerceAtLeast(0)
        val tweets = maxOf(json.optLong("tweets"), blocked)
        val fresh = System.currentTimeMillis() - json.optLong("lastSeen") < 12_000
        val inX = fresh && json.optBoolean("fg", false)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!inX) {
            manager.cancel(CAPSULE_ID)
            return
        }
        // The chip only fits a handful of characters; the full ratio lives on the card.
        val chipText = "已拦$blocked"
        val icon = runCatching { R.drawable.ic_notification }.getOrElse { android.R.drawable.stat_notify_error }
        val builder = Notification.Builder(context, CAPSULE_CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("XBlocker")
            .setContentText("本轮已拦截 $blocked / $tweets 条")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (Build.VERSION.SDK_INT >= 36) {
            // Red = removed spam share, green = kept tweets share. The promotion request
            // extras key mirrors NotificationCompat's setRequestPromotedOngoing;
            // shortCriticalText feeds the capsule form.
            // Bound both segments so their sum cannot overflow Android's Int progress range.
            val spam = blocked.coerceIn(1L, Int.MAX_VALUE.toLong() / 2).toInt()
            val kept = (tweets - blocked).coerceIn(1L, Int.MAX_VALUE.toLong() / 2).toInt()
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
                .setStyle(Notification.ProgressStyle()
                    .setProgressSegments(listOf(
                        Notification.ProgressStyle.Segment(spam).setColor(SPAM_COLOR),
                        Notification.ProgressStyle.Segment(kept).setColor(KEPT_COLOR)))
                    .setProgress(spam)
                    .setStyledByProgress(true))
                .setShortCriticalText(chipText)
        } else {
            builder.setProgress(maxOf(100, tweets).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                blocked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false)
        }
        XiaomiFocusNotification.apply(context, builder, icon, blocked, tweets)
        manager.notify(CAPSULE_ID, builder.build())
    }
}
