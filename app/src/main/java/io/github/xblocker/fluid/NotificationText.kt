package io.github.xblocker.fluid

import android.content.Context
import io.github.xblocker.BuildConfig
import io.github.xblocker.R
import io.github.xblocker.i18n.AppLanguage

/** Text is injected into the pure payload builder; no Android resources are needed there. */
internal data class NotificationText(
    val channel: String = "Live blocking status",
    val channelDescription: String = "Shows blocking progress while using X",
    val focusChannel: String = "Focus notification conversion",
    val focusDescription: String = "Converts XBlocker live status to Xiaomi focus notifications",
    val short: String = "Blocked %1\$s",
    val session: String = "Blocked %1\$s / %2\$s this session",
    val total: String = "XBlocker · Blocked %1\$s",
    val fraction: String = "Blocked %1\$s / %2\$s",
    val percent: String = "XBlocker · Blocked %1\$s%% this session",
    val label: String = "Blocked",
) {
    companion object {
        fun from(context: Context): NotificationText = runCatching {
            // A host process must never resolve our resource IDs against X's resources.
            val module = if (context.packageName == BuildConfig.APPLICATION_ID) AppLanguage.context(context)
            else context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            NotificationText(
                module.getString(R.string.live_status), module.getString(R.string.live_description),
                module.getString(R.string.focus_notification_conversion), module.getString(R.string.focus_description),
                module.getString(R.string.blocked_short), module.getString(R.string.blocked_session),
                module.getString(R.string.blocked_total), module.getString(R.string.blocked_fraction),
                module.getString(R.string.blocked_percent), module.getString(R.string.blocked_label),
            )
        }.getOrDefault(NotificationText()) // App hiding must not break host notifications.
    }
}
