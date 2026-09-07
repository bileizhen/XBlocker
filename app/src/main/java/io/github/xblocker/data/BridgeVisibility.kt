package io.github.xblocker.data

import android.content.Context
import android.content.Intent
import android.net.Uri

object BridgeVisibility {
    private const val AUTHORITY = "io.github.bileizhen.xblocker.bridge"
    /**
     * A persisted URI grant is what makes the provider resolvable from X on this
     * device; Android clears it whenever this APK is updated, so every entry point
     * re-issues it. Idempotent, and scoped to read access for X only.
     */
    fun grant(context: Context) {
        runCatching {
            context.grantUriPermission("com.twitter.android", Uri.parse("content://$AUTHORITY"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
