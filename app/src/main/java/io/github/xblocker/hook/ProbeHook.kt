package io.github.xblocker.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary discovery probe for X 12.23.1: the Jackson entry is gone and the new timeline
 * decode path had to be located at runtime. Samples travel through the module's own bridge
 * report because the X process logcat channel proved unreliable on this OEM. Remove once the
 * real adapter exists.
 *
 * Findings so far (12.23.1-prod.01): timeline JSON is decoded by kotlinx.serialization's
 * streaming decoder `kotlinx.serialization.json.internal.b` (stable class name, obfuscated
 * members) driven by wrapper classes hidden in android.os/android.content; document text is
 * built via StringBuilder and never passes through byte/char String constructors.
 */
object ProbeHook {
    private const val TAG = "XBlockerProbe"
    private val logged = AtomicInteger()
    private val lastLog = AtomicLong()
    private val samples = ConcurrentLinkedQueue<String>()

    /** Class loader of the host app, captured when the probe is installed. */
    private var loader: ClassLoader? = null

    fun install(loader: ClassLoader) {
        this.loader = loader
        val specs = listOf(
            arrayOf(ByteArray::class.java),
            arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            arrayOf(ByteArray::class.java, java.nio.charset.Charset::class.java),
            arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, java.nio.charset.Charset::class.java),
            arrayOf(CharArray::class.java),
            arrayOf(CharArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
        )
        var hooked = 0
        for (spec in specs) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val ctor = String::class.java.getConstructor(*spec) as Constructor<String>
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        runCatching { inspectJson(p.thisObject as? String) }
                    }
                })
                hooked++
            }
        }
        addSample("probe installed on $hooked String constructors")
        Thread({ installMoshiProbe() }, "XBlocker-probe").apply { isDaemon = true }.start()
        Thread({ installKotlinxProbe() }, "XBlocker-probe2").apply { isDaemon = true }.start()
    }

    /**
     * kotlinx decoders. Runtime class names are remapped by X (stacks show android.os.*
     * / android.content.* wrappers that rotate between builds), but the logical
     * kotlinx/... names still resolve through the app classloader and Method objects
     * hook fine. E(deserializer) returns each decoded model; constructors carrying the
     * whole document String expose the JSON text.
     */
    private fun installKotlinxProbe() {
        val targets = listOf(
            "kotlinx.serialization.json.internal.b",
            "kotlinx.serialization.json.internal.f0",
            "kotlinx.serialization.json.internal.k",
            "kotlinx.serialization.json.Json",
            "kotlinx.serialization.json.c",
        )
        for (attempt in 1..60) {
            val resolved = targets.mapNotNull { name ->
                runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { name to it }
            }
            if (resolved.isNotEmpty()) {
                var hooked = 0
                for ((name, clazz) in resolved) {
                    runCatching {
                        for (ctor in clazz.declaredConstructors) {
                            ctor.parameterTypes.forEachIndexed { index, type ->
                                if (type == String::class.java) {
                                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                                        override fun afterHookedMethod(p: MethodHookParam) {
                                            runCatching { inspectJson(p.args[index] as? String, tag = "doc[$name]") }
                                        }
                                    })
                                    hooked++
                                }
                            }
                        }
                        for (method in clazz.declaredMethods) {
                            val ps = method.parameterTypes
                            if (method.name == "E" && ps.size == 1) {
                                hook(method) { p -> sampleResult("kx[$name]", p.result) }
                                hooked++
                            }
                            // The Json facade: any method taking a String may be the
                            // decodeFromString entry carrying the whole document.
                            else if (ps.any { it == String::class.java }) {
                                hook(method) { p ->
                                    runCatching {
                                        p.args.forEachIndexed { i, arg ->
                                            if (arg is String && arg.length >= 2000) inspectJson(arg, tag = "str[$name#${method.name}]")
                                        }
                                    }
                                }
                                hooked++
                            }
                        }
                    }
                }
                addSample("kotlinx probe installed on $hooked methods over ${resolved.size} classes (attempt $attempt)")
                return
            }
            Thread.sleep(1000)
        }
        addSample("kotlinx decoders never appeared")
    }

    /** Moshi chokepoints: `u.fromJson` is the final base entry every adapter calls. */
    private fun installMoshiProbe() {
        for (attempt in 1..30) {
            val adapter = runCatching { Class.forName("com.squareup.moshi.u", false, loader) }.getOrNull()
            val reader = runCatching { Class.forName("com.squareup.moshi.a0", false, loader) }.getOrNull()
            if (adapter != null && reader != null) {
                var hooked = 0
                runCatching {
                    for (method in adapter.declaredMethods) {
                        if (method.parameterTypes.size != 1) continue
                        if (method.parameterTypes[0] != String::class.java && method.parameterTypes[0].name != "okio.m") continue
                        hook(method) { p -> sampleResult("adapter", p.result) }
                        hooked++
                    }
                }
                addSample("moshi probe installed on $hooked methods (attempt $attempt)")
                return
            }
            Thread.sleep(1000)
        }
        addSample("moshi classes never appeared")
    }

    private fun hook(method: java.lang.reflect.Member, body: (XC_MethodHook.MethodHookParam) -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                runCatching { body(p) }
            }
        })
    }

    private val noise = listOf("unifiedcards", "gms", "socure", "withpersona", "featureswitches", "Palette", "thrift.onboarding", "thrift.periscope", "thrift.video")

    private fun sampleResult(tag: String, result: Any?) {
        if (result == null) return
        val className = result.javaClass.name
        if (noise.any { it in className }) return
        val text = runCatching { result.toString() }.getOrDefault("").replace('\n', ' ').replace('\r', ' ')
        if (text.length < 1500) return
        addSample("$tag $className len=${text.length} toS=${text.take(220)} || ${stackText()}")
    }

    private fun inspectJson(text: String?, tag: String = "json-string") {
        if (text == null || text.length < 800) return
        val first = text[0]
        if (first != '{' && first != '[') return
        inspect(text, tag, headLen = 500)
    }

    private fun inspect(text: String?, tag: String = "string", headLen: Int = 180) {
        if (text == null || text.length < 60 || logged.get() >= 60) return
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastLog.get()
        if (now - previous < 250 && !lastLog.compareAndSet(previous, now)) return
        if (logged.incrementAndGet() > 60) return
        lastLog.set(now)
        val head = text.take(headLen).replace('\n', ' ').replace('\r', ' ')
        addSample("$tag len=${text.length} head=$head || ${stackText()}")
    }

    private fun stackText(): String = Throwable().stackTrace
        .filter { !it.className.startsWith("io.github.xblocker.hook.ProbeHook") && !it.className.startsWith("de.robv.android.xposed") }
        .take(10)
        .joinToString(" <- ") { "${it.className}.${it.methodName}" }

    private fun addSample(sample: String) {
        if (samples.size < 60) {
            samples.add(sample.take(900))
            Log.e(TAG, sample.take(300))
        }
    }

    fun drain(): org.json.JSONArray {
        // Snapshot, not consume: every 5s report would otherwise overwrite the stored
        // array with an empty one before the developer reads it.
        val out = org.json.JSONArray()
        for (s in samples) {
            if (out.length() >= 40) break
            out.put(s)
        }
        return out
    }
}
