package io.github.xblocker.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Native per-app locales on Android 13+, configuration contexts on Android 9–12. */
object AppLanguage {
    val tags = listOf("", "en", "zh-Hans", "zh-Hant")
    private const val PREFERENCES = "language"
    private const val KEY = "tag"

    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales
            .let { if (it.isEmpty) "" else it[0].toLanguageTag() }
    } else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun context(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = current(base)
        if (tag.isEmpty()) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }

    fun set(activity: Activity, tag: String) {
        require(tag in tags)
        if (current(activity) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            // Persist before recreation so the new Activity starts in the selected language.
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }
}
