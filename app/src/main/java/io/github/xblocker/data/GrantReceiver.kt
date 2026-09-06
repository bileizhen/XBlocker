package io.github.xblocker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-issues the X provider grant right after an APK update cleared it. */
class GrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeVisibility.grant(context)
    }
}
