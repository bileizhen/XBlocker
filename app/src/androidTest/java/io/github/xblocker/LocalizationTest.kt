package io.github.xblocker

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.core.ConfigCodec
import io.github.xblocker.core.FilterSettings
import io.github.xblocker.i18n.LocalizedText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private fun localized(tag: String) = InstrumentationRegistry.getInstrumentation().targetContext.let {
        it.createConfigurationContext(Configuration(it.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        })
    }

    @Test fun languagesAndUnsupportedLocaleFallbackResolveCorrectly() {
        for ((tag, expected) in listOf("en" to "Settings", "zh-CN" to "设置",
            "zh-Hans" to "设置", "zh-TW" to "設定", "zh-Hant" to "設定", "fr" to "Settings")) {
            assertEquals(tag, expected, localized(tag).getString(R.string.settings))
        }
    }

    @Test fun everyLocalizedFormatCanBeRenderedByAndroid() {
        val placeholder = Regex("%(\\d+)\\$[sd]")
        val base = localized("en")
        for (field in R.string::class.java.fields) {
            val id = field.getInt(null)
            val source = base.getString(id)
            val sourceArgs = placeholder.findAll(source).map { it.value }.toSet()
            for (tag in listOf("en", "zh-Hans", "zh-Hant")) {
                val context = localized(tag)
                val value = context.getString(id)
                assertEquals("${field.name}: $tag", sourceArgs, placeholder.findAll(value).map { it.value }.toSet())
                if (sourceArgs.isNotEmpty()) {
                    val count = placeholder.findAll(value).maxOf { it.groupValues[1].toInt() }
                    val rendered = context.getString(id, *Array<Any>(count) { 12 })
                    assertFalse("${field.name}: $tag", rendered.contains(placeholder))
                }
            }
        }
    }

    @Test fun translatedCategoriesDoNotAlterPersistedRules() {
        val settings = FilterSettings(disabledCategories = setOf("仇恨用语"), whitelist = setOf("alice"))
        val encoded = ConfigCodec.encode(settings).toString()
        assertEquals("Hateful language", LocalizedText.resolve(localized("en"), "仇恨用语"))
        assertEquals("仇恨用語", LocalizedText.resolve(localized("zh-Hant"), "仇恨用语"))
        assertEquals("Community category", LocalizedText.resolve(localized("en"), "Community category"))
        assertEquals(encoded, ConfigCodec.encode(settings).toString())
        assertEquals("Sync failed; local rules kept: Invalid rules format",
            LocalizedText.resolve(localized("en"), "同步失败，保留本地词库：词库格式无效"))
    }
}
