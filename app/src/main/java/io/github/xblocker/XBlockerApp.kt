package io.github.xblocker

import android.app.Application
import io.github.xblocker.data.BridgeVisibility
import io.github.xblocker.data.CloudSync

class XBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Also re-issued by GrantReceiver after APK updates clear the persisted grant.
        BridgeVisibility.grant(this)
        CloudSync.schedule(this)
    }
}
