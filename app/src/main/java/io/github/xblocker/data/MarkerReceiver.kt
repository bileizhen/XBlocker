package io.github.xblocker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import org.json.JSONObject

/**
 * Receives the activation marker the X-side hook broadcasts independently of the provider
 * bridge (explicit package broadcast survives cleared URI grants). Only X's UID and our own
 * UID may write the marker.
 */
class MarkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        if (uid != Process.myUid() && "com.twitter.android" !in packages) return
        val payload = intent.getStringExtra("json") ?: return
        if (payload.length > 4_000) return
        runCatching { Repository(context).reportMarker(JSONObject(payload)) }
    }
}
