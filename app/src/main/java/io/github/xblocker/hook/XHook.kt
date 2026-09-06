package io.github.xblocker.hook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import dalvik.system.DexFile
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xblocker.core.ConfigCodec
import io.github.xblocker.core.GraphQlResponseFilter
import io.github.xblocker.core.ReplayInput
import io.github.xblocker.core.RuleEngine
import io.github.xblocker.core.TimelineFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Marker broadcast: reports that the module is running inside X through a channel
 * independent of the provider bridge, so the app can tell "never loaded by the
 * framework" apart from "loaded but the bridge is unreachable".
 */
private const val MARKER_ACTION = "io.github.xblocker.MARKER"
private const val MODULE_PACKAGE = "io.github.xblocker"

private fun sendMarker(context: Context, payload: JSONObject) {
    runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        context.sendBroadcast(Intent(MARKER_ACTION).setPackage(MODULE_PACKAGE)
            .putExtra("json", payload.put("pid", Process.myPid()).put("version", version).toString()))
    }
}

class XHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.twitter.android" || param.processName != param.packageName) return
        // 12.23.1 research probe, disabled for daily builds: install(param.classLoader)
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val context = p.args[0] as Context
                val app = p.thisObject as Application
                runCatching { Runtime(context, app, param.classLoader).start() }.onFailure {
                    XposedBridge.log("XBlocker: initialization failed: ${it.javaClass.simpleName}")
                    sendMarker(context, JSONObject().put("phase", "init-failed")
                        .put("error", "${it.javaClass.simpleName}: ${it.message?.take(160)}"))
                }
            }
        })
    }

    private class Runtime(private val context: Context, private val app: Application, private val loader: ClassLoader) {
        private val bridge = Uri.parse("content://io.github.xblocker.bridge")
        private val queue = ConcurrentLinkedQueue<JSONObject>()
        private val responses = AtomicLong()
        private val seen = AtomicLong()
        private val filtered = AtomicLong()
        private val depth = ThreadLocal.withInitial { 0 }
        @Volatile private var filter: TimelineFilter? = null
        private val stats = io.github.xblocker.core.FilterStats()
        @Volatile private var enabled = false
        private var lastSnapshot = ""
        private var hooks = 0
        private var adapter = ""
        @Volatile private var error = ""
        private var lastBridgeLog = 0L
        @Volatile private var prefsFallbackLogged = false
        private val prefsFailureLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        private val resumed = java.util.concurrent.atomic.AtomicInteger()
        private val fgReportTicket = AtomicLong()
        private val bridgeExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "XBlocker-bridge").apply { isDaemon = true } }

        fun start() {
            // Whether X currently shows any activity; drives fluid-cloud capsule visibility.
            // Cross-activity transitions debounce out: the delayed report always reads the
            // live counter, and only the newest boundary schedules a report.
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    if (resumed.incrementAndGet() == 1) reportForegroundSoon()
                }
                override fun onActivityPaused(activity: android.app.Activity) {
                    if (resumed.decrementAndGet() == 0) reportForegroundSoon()
                }
                override fun onActivityStarted(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {}
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            if (version == "12.16.3" || version.startsWith("12.16.3-")) {
                runCatching {
                    val responseFilter = GraphQlResponseFilter(loader, ::transform)
                    val call = loader.loadClass("okhttp3.internal.connection.RealCall")
                    val method = call.getDeclaredMethod("getResponseWithInterceptorChain\$okhttp")
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            if (!enabled || p.hasThrowable()) return
                            val original = p.result ?: return
                            try { p.result = responseFilter.filter(original) }
                            catch (e: Exception) { error = "过滤已跳过：${e.javaClass.simpleName}" }
                        }
                    })
                    hooks++
                    adapter = "okhttp3/GraphQL (X 12.16.3)"
                }.onFailure { error = "12.16.3 数据入口初始化失败：${it.javaClass.simpleName}" }
            } else {
                installJackson()
            }
            if (hooks == 0 && error.isEmpty()) error = "未找到兼容的数据入口，需要适配此 X 版本"
            XposedBridge.log("XBlocker: adapter=$adapter hooks=$hooks")
            sendMarker(context, JSONObject().put("phase", "started").put("hooks", hooks).put("adapter", adapter).put("error", error))
            bridgeExecutor.scheduleWithFixedDelay({ refreshAndReport() }, 0, 5, TimeUnit.SECONDS)
        }

        private fun installJackson() {
            val names = linkedSetOf("com.fasterxml.jackson.core.JsonFactory", "com.fasterxml.jackson.core.e")
            // Only inspect the small Jackson core namespace; do not load every application class.
            runCatching {
                @Suppress("DEPRECATION")
                val dex = DexFile(context.applicationInfo.sourceDir)
                try { dex.entries().asSequence().filter { it.startsWith("com.fasterxml.jackson.core.") && !it.substringAfter("com.fasterxml.jackson.core.").contains('.') }.forEach(names::add) }
                finally { dex.close() }
            }
            for (name in names) {
                val cls = XposedHelpers.findClassIfExists(name, loader) ?: continue
                // All parser-construction overloads are candidates: the class itself is
                // identified by having both the 1-arg InputStream and byte[] factories.
                val factories = cls.declaredMethods.filter { m ->
                    m.parameterTypes.size == 1 && m.parameterTypes[0] in setOf(InputStream::class.java, ByteArray::class.java) &&
                        m.returnType.name.startsWith("com.fasterxml.jackson.core.")
                }
                if (factories.none { it.parameterTypes[0] == InputStream::class.java } || factories.none { it.parameterTypes[0] == ByteArray::class.java }) continue
                val textParams = setOf(InputStream::class.java, ByteArray::class.java, String::class.java, CharArray::class.java, java.io.Reader::class.java)
                val methods = cls.declaredMethods.filter { m ->
                    val ps = m.parameterTypes
                    (ps.size == 1 && ps[0] in textParams || ps.size == 3 && ps[0] == ByteArray::class.java &&
                        ps[1] == Int::class.javaPrimitiveType && ps[2] == Int::class.javaPrimitiveType) &&
                        m.returnType.name.startsWith("com.fasterxml.jackson.core.")
                }
                for (method in methods) {
                    hookTextEntry(method)
                    hooks++
                }
                adapter = name
                break
            }
            // Retained as a parser fallback for clients using generated LoganSquare mappers.
            runCatching {
                val mapper = XposedHelpers.findClassIfExists("com.bluelinelabs.logansquare.JsonMapper", loader) ?: return@runCatching
                val overloads = mapper.declaredMethods.filter { m ->
                    m.name in setOf("parse", "parseList") && m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] in setOf(InputStream::class.java, ByteArray::class.java, String::class.java, CharArray::class.java)
                }
                for (method in overloads) {
                    hookTextEntry(method)
                    hooks++
                }
                if (overloads.isNotEmpty() && adapter.isEmpty()) adapter = "com.bluelinelabs.logansquare.JsonMapper"
            }
        }

        /** Rewrites the text payload of a hooked parse entry before the host consumes it. */
        private fun hookTextEntry(method: java.lang.reflect.Member) {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!enabled || (depth.get() ?: 0) > 0) return
                    depth.set(1); p.setObjectExtra("xblocker_owned", true)
                    try {
                        when (val input = p.args[0]) {
                            is InputStream -> p.args[0] = ReplayInput.transform(input, ::transform)
                            is ByteArray -> {
                                val offset = (p.args.getOrNull(1) as? Int) ?: 0
                                val length = (p.args.getOrNull(2) as? Int) ?: input.size
                                if (length in 1..TimelineFilter.MAX_CHARS && offset >= 0 && offset + length <= input.size) {
                                    val text = String(input, offset, length, Charsets.UTF_8)
                                    if (text.toByteArray(Charsets.UTF_8).contentEquals(input.copyOfRange(offset, offset + length))) {
                                        val transformed = transform(text).toByteArray(Charsets.UTF_8)
                                        p.args[0] = transformed
                                        if (p.args.size >= 3) { p.args[1] = 0; p.args[2] = transformed.size }
                                    }
                                }
                            }
                            is String -> p.args[0] = transform(input)
                            is CharArray -> if (input.size <= TimelineFilter.MAX_CHARS) p.args[0] = transform(String(input)).toCharArray()
                            else -> Unit
                        }
                    } catch (e: Exception) { error = "过滤已跳过：${e.javaClass.simpleName}" }
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.getObjectExtra("xblocker_owned") == true) depth.set(0)
                }
            })
        }

        /** Push a foreground-state report within ~0.4s so the capsule reacts immediately. */
        private fun reportForegroundSoon() {
            val ticket = fgReportTicket.incrementAndGet()
            runCatching { bridgeExecutor.schedule({ if (fgReportTicket.get() == ticket) refreshAndReport() }, 400, TimeUnit.MILLISECONDS) }
        }

        private fun transform(input: String): String {
            responses.incrementAndGet()
            val result = filter?.filter(input) ?: return input
            if (result.recognized) seen.incrementAndGet()
            filtered.addAndGet(result.events.size.toLong())
            result.events.forEach { event -> if (queue.size < 100) queue.add(JSONObject()
                .put("id", event.id).put("handle", event.handle).put("reason", event.reason).put("rule", event.rule).put("category", event.category)) }
            return result.json
        }

        private fun refreshAndReport() {
            try {
                val snapshot = context.contentResolver.call(bridge, "snapshot", null, null)?.getString("json") ?: return
                applySnapshot(snapshot)
                val batch = queue.toList().take(100)
                val report = JSONObject().put("hooks", hooks).put("adapter", adapter).put("pid", Process.myPid())
                    .put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    .put("fg", resumed.get() > 0)
                    .put("seen", seen.get()).put("responses", responses.get()).put("filtered", filtered.get()).put("error", error)
                    .put("rules", filter?.ruleCount ?: 0).put("rejected", filter?.rejectedCount ?: 0)
                    .put("probe", JSONArray()) // Clear diagnostics retained from research builds.
                    .put("events", JSONArray(batch))
                    .put("fp", JSONArray(stats.fingerprint.toList().sorted()))
                stats.toMap().forEach { (key, value) -> report.put(key, value) }
                context.contentResolver.call(bridge, "report", null, Bundle().apply { putString("json", report.toString()) })
                batch.forEach(queue::remove)
            } catch (e: Exception) {
                // Filtering must not depend on provider visibility: some OEM builds keep the
                // provider hidden right after an APK update cleared the URI grant. Fall back
                // to reading the module prefs LSPosed exposes to scoped processes.
                val snapshot = prefsSnapshot()
                if (applySnapshot(snapshot) && !prefsFallbackLogged) {
                    prefsFallbackLogged = true
                    XposedBridge.log("XBlocker: prefs fallback active (rules=${filter?.ruleCount ?: 0})")
                }
                val now = System.currentTimeMillis()
                if (now - lastBridgeLog > 60_000) {
                    lastBridgeLog = now
                    if (snapshot == null) XposedBridge.log("XBlocker: bridge unavailable: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
                    sendMarker(context, JSONObject().put("phase", "bridge-failed")
                        .put("fallback", snapshot != null)
                        .put("error", "${e.javaClass.simpleName}: ${e.message?.take(160)}"))
                }
            }
        }

        private fun applySnapshot(snapshot: String?): Boolean {
            if (snapshot == null || snapshot == lastSnapshot) return snapshot != null
            val json = JSONObject(snapshot)
            val settings = ConfigCodec.decode(json.getJSONObject("settings"))
            filter = TimelineFilter(RuleEngine(settings, json.getString("cloud")), stats)
            enabled = settings.enabled
            lastSnapshot = snapshot
            return true
        }

        /** Rebuilds the snapshot JSON directly from the module's shared prefs file. */
        private fun prefsSnapshot(): String? = runCatching {
            val prefs = de.robv.android.xposed.XSharedPreferences("io.github.xblocker", "xblocker")
            // Absent "settings" means the app runs on defaults; "{}" decodes to the same.
            val settings = prefs.getString("settings", null) ?: "{}"
            val cloud = prefs.getString("cloud", null)
            if (cloud == null) {
                if (prefsFailureLogged.compareAndSet(false, true))
                    XposedBridge.log("XBlocker: prefs fallback unreadable (cloud=false, file=${prefs.file?.absolutePath})")
                return@runCatching null
            }
            JSONObject().put("settings", JSONObject(settings)).put("cloud", cloud).toString()
        }.onFailure { if (prefsFailureLogged.compareAndSet(false, true)) XposedBridge.log("XBlocker: prefs fallback error: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }.getOrNull()
    }
}
