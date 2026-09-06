package io.github.xblocker.fluid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.xblocker.R
import io.github.xblocker.data.Repository
import io.github.xblocker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive with a minimal-importance foreground notification. Capsule
 * updates are event-driven from ModuleProvider (binder calls from X guarantee CPU); the
 * loop here is only a backup to expire the capsule when X stops reporting.
 */
class FluidCloudService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(FOREGROUND_CHANNEL, "拦截监控", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持过滤监控运行；仅在 X 前台时显示流体云"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            })
        FluidStatus.ensureChannels(this)
        ServiceCompat.startForeground(this, FOREGROUND_ID, foregroundNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        runCatching { tick() }
        scope.launch {
            while (isActive) {
                delay(5_000)
                runCatching { tick() }.onFailure { Log.w(TAG, "tick failed: ${it.message}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(FluidStatus.CAPSULE_ID)
        scope.cancel()
        super.onDestroy()
    }

    private fun tick() = FluidStatus.onDiagnostics(this, Repository(this).diagnostics())

    // A stale incremental build once shipped a dex whose R class lacked the icon field,
    // crashing the app in a restart loop; fall back to a system icon instead of dying.
    private fun foregroundNotification(): Notification = runCatching {
        Notification.Builder(this, FOREGROUND_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("XBlocker")
            .setContentText("拦截监控运行中 · 打开 X 时显示实时状态")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }.getOrElse {
        Notification.Builder(this, FOREGROUND_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("XBlocker")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "XBlocker.Fluid"
        private const val FOREGROUND_CHANNEL = "fluid_status_foreground"
        private const val FOREGROUND_ID = 43
        private const val ACTION_STOP = "io.github.xblocker.fluid.STOP"
        fun start(context: Context) = context.startForegroundService(Intent(context, FluidCloudService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, FluidCloudService::class.java).setAction(ACTION_STOP))
    }
}
