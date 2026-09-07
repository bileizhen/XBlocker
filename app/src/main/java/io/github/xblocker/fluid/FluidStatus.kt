package io.github.xblocker.fluid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import io.github.xblocker.ui.MainActivity

/**
 * Posts/cancels the native capsule notification. The primary caller is X's process, so
 * clearing the XBlocker task does not remove the owner of the live notification. The module
 * provider may use the same class only as a fallback when X cannot post.
 * Android owns the timeout so a dead reporting process cannot leave a stale capsule.
 */
object FluidStatus {
    const val CAPSULE_ID = 42
    const val REPORT_TIMEOUT_MS = 12_000L
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
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
    fun onDiagnostics(context: Context, json: org.json.JSONObject): Boolean {
        val blocked = json.optLong("blocked").coerceAtLeast(0)
        val tweets = maxOf(json.optLong("tweets"), blocked)
        val age = System.currentTimeMillis() - json.optLong("lastSeen")
        val fresh = age >= 0 && age < REPORT_TIMEOUT_MS
        val inX = fresh && json.optBoolean("fg", false)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!inX) {
            manager.cancel(CAPSULE_ID)
            return true
        }
        // The chip only fits a handful of characters; the full ratio lives on the card.
        val chipText = "已拦$blocked"
        val builder = Notification.Builder(context, CAPSULE_CHANNEL)
            .setSmallIcon(notificationIcon(context))
            .setContentTitle("XBlocker")
            .setContentText("本轮已拦截 $blocked / $tweets 条")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(REPORT_TIMEOUT_MS - age)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent(context))
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
        return runCatching {
            manager.notify(CAPSULE_ID, builder.build())
        }.isSuccess
    }

    private fun notificationIcon(context: Context): Icon = ModuleIcon.notification(context)

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, CAPSULE_ID,
        Intent().setClassName(MODULE_PACKAGE, MainActivity::class.java.name),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
