package io.github.xblocker.fluid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Xml
import io.github.xblocker.R
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * Resolves the module's status icon from any publisher. The module app reads its own
 * resource; inside X the module package is hidden by visibility filtering, so the same
 * vector is inflated from an embedded copy instead of degrading to a framework glyph.
 */
internal object ModuleIcon {
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
    // Mirrors res/drawable/ic_notification.xml; keep both in sync.
    private const val VECTOR = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="24dp" android:height="24dp"
        android:viewportWidth="24" android:viewportHeight="24">
        <path android:fillColor="#00000000"
            android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8" android:strokeLineJoin="round"
            android:pathData="M12,2.5 L19.5,5.4 L19.5,11 C19.5,15.9 16.2,19.8 12,21.5 C7.8,19.8 4.5,15.9 4.5,11 L4.5,5.4 Z" />
        <path android:fillColor="#00000000"
            android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8" android:strokeLineCap="round"
            android:pathData="M9,9 L15,15 M15,9 L9,15" />
    </vector>"""

    fun notification(context: Context): Icon = runCatching {
        val module = if (context.packageName == MODULE_PACKAGE) context
        else context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        Icon.createWithResource(module, R.drawable.ic_notification)
    }.getOrElse { embedded(context) }

    private fun embedded(context: Context): Icon = runCatching {
        val parser = Xml.newPullParser().apply { setInput(StringReader(VECTOR)) }
        var type = parser.next()
        while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) type = parser.next()
        val drawable = checkNotNull(Drawable.createFromXml(context.resources, parser))
        val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        Icon.createWithBitmap(bitmap)
    }.getOrElse { Icon.createWithResource(context, android.R.drawable.stat_notify_error) }
}
