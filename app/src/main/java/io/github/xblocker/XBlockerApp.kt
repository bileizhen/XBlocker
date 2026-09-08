package io.github.xblocker

import android.app.Application
import io.github.xblocker.data.BridgeVisibility
import io.github.xblocker.data.CloudSync

class XBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Remove the obsolete monitor notification/channel left by older builds.
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                ?.deleteNotificationChannel("fluid_status_foreground")
        }
        // Make the LSPosed-shared configuration readable from X after an OEM kills the
        // provider process when the XBlocker task is dismissed. Also restores the
        // module-owned notification channels cleared with the app's data.
        runCatching { io.github.xblocker.data.Repository(this).createChannelsIfEnabled() }
        // Also re-issued by GrantReceiver after APK updates clear the persisted grant.
        BridgeVisibility.grant(this)
        CloudSync.schedule(this)
    }
}
