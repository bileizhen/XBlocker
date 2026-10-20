package io.github.xblocker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-issues the X provider grant right after an APK update cleared it. */
class GrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BridgeVisibility.grant(context)
        val pending = goAsync()
        kotlin.concurrent.thread(name = "XBlocker-update-enqueue") {
            try { UpdateActivation.afterPackageReplaced(context)?.result?.get(8, java.util.concurrent.TimeUnit.SECONDS) }
            catch (error: Exception) { android.util.Log.w("XBlockerUpdate", "Update enqueue interrupted", error) }
            finally { pending.finish() }
        }
    }
}
