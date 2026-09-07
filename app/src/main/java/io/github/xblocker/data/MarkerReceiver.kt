package io.github.xblocker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import org.json.JSONObject

/**
 * Receives the activation marker the hooks broadcast independently of the provider bridge
 * (explicit package broadcast survives cleared URI grants). Only X's UID, system-side hook
 * processes (uid < 10000) and our own UID may write markers.
 */
class MarkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && uid >= 10_000) {
            val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
            if ("com.twitter.android" !in packages) return
        }
        val payload = intent.getStringExtra("json") ?: return
        if (payload.length > 4_000) return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (json.optString("phase").startsWith("hook@")) Repository(context).reportHookMarker(json)
        else Repository(context).reportMarker(json)
    }
}
