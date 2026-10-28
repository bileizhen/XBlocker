package io.github.xblocker

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xblocker.core.ConfigCodec
import io.github.xblocker.core.FilterSettings
import io.github.xblocker.core.RuleEngine
import io.github.xblocker.core.Tweet
import io.github.xblocker.data.AppUpdates
import io.github.xblocker.data.Repository
import io.github.xblocker.hook.HostConfigCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FpaCompatibilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fpaManifestRequiresOnlyLegacyApiAndXScope() {
        assumeTrue(BuildConfig.FPA_COMPAT)
        val app = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(82, app.metaData.getInt("xposedminversion"))
        assertFalse(app.metaData.containsKey("xposedsharedprefs"))
        assertEquals(listOf("com.twitter.android"), context.resources.getStringArray(app.metaData.getInt("xposedscope")).toList())
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertFalse(providers.any { it.name.endsWith("XposedServiceProvider") })
        assertTrue(providers.any { it.name.endsWith("ModuleProvider") })
    }

    @Test fun savedHostConfigSurvivesNewCacheInstanceAndKeepsDisabledState() {
        withEmptyCache {
            val settings = FilterSettings(enabled = false, cloudEnabled = false, customRules = "fpa-test",
                whitelist = setOf("allowed"), blockReposts = true)
            val snapshot = JSONObject().put("settings", ConfigCodec.encode(settings)).put("cloud", "offline-rule").toString()
            HostConfigCache(context).save(snapshot)
            val recovered = HostConfigCache(context).read()
            assertEquals(snapshot, recovered)
            assertEquals(settings, ConfigCodec.decode(JSONObject(recovered!!).getJSONObject("settings")))
        }
    }

    @Test fun customRulesStillMatchFromCachedSnapshot() {
        withEmptyCache {
            val settings = FilterSettings(cloudEnabled = false, customRules = "fpa-test", whitelist = setOf("allowed"))
            HostConfigCache(context).save(JSONObject().put("settings", ConfigCodec.encode(settings)).put("cloud", "").toString())
            val saved = JSONObject(HostConfigCache(context).read()!!)
            val engine = RuleEngine(ConfigCodec.decode(saved.getJSONObject("settings")), saved.getString("cloud"))
            assertNotNull(engine.match(Tweet("1", "fpa-test", handle = "other", isReply = true)))
            assertNull(engine.match(Tweet("2", "fpa-test", handle = "allowed", isReply = true)))
        }
    }

    @Test fun invalidSnapshotCannotReplaceLastUsableConfiguration() {
        withEmptyCache {
            val cache = HostConfigCache(context)
            val valid = JSONObject().put("settings", ConfigCodec.encode(FilterSettings())).put("cloud", "safe").toString()
            cache.save(valid)
            for (bad in listOf("{", "{}", "{\"settings\":{},\"cloud\":null}", "x".repeat(1_000_001))) {
                assertTrue(runCatching { cache.save(bad) }.isFailure)
                assertEquals(valid, cache.read())
            }
        }
    }

    @Test fun fpaDoesNotOfferStandardEditionUpdatesOrSystemFocusHooks() {
        assumeTrue(BuildConfig.FPA_COMPAT)
        assertNull(AppUpdates.check())
        assertFalse(Repository(context).autoUpdate())
        assertFalse(Repository(context).focusNotification())
    }

    private fun withEmptyCache(block: () -> Unit) {
        // This is the module test UID, never X's actual private configuration.
        val prefs = context.getSharedPreferences("xblocker_fpa_config", Context.MODE_PRIVATE)
        val previous = prefs.getString("snapshot", null)
        prefs.edit().clear().commit()
        try { block() } finally {
            prefs.edit().clear().apply { if (previous != null) putString("snapshot", previous) }.commit()
        }
    }
}
