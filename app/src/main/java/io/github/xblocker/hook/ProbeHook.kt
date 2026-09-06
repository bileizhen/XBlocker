package io.github.xblocker.hook

import android.content.Context
import android.util.Log
import dalvik.system.DexFile
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * v10: hook EVERY method in the Jackson core namespace (all subpackages) whose return
 * type is a Jackson type, and count invocations. Reveals which factory actually carries
 * the timeline on X 12.16.3, where the previously-identified factory barely fires.
 */
object ProbeHook {
    private const val TAG = "XBlockerProbe"
    private val fired = ConcurrentHashMap<String, AtomicInteger>()
    private val samples = ConcurrentLinkedQueue<String>()

    fun install(context: Context, loader: ClassLoader) {
        Thread({
            runCatching {
                // 0. Network ground truth: what do the responses actually look like?
                runCatching {
                    val builder = Class.forName("okhttp3.Response\$Builder", false, loader)
                    for (method in builder.declaredMethods) {
                        if (method.name != "build" || method.parameterTypes.isNotEmpty()) continue
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                runCatching {
                                    val resp = p.result ?: return
                                    val req = resp.javaClass.getMethod("request").invoke(resp)
                                    val url = req.javaClass.getMethod("url").invoke(req).toString()
                                    val ct = resp.javaClass.getMethod("header", String::class.java).invoke(resp, "content-type")
                                    val n = fired.computeIfAbsent("resp $url") { AtomicInteger() }.incrementAndGet()
                                    if (n <= 1) addSample("RESP ct=$ct url=${url.take(140)}")
                                }
                            }
                        })
                    }
                    addSample("response builder probe on")
                }
                for (name in listOf("okhttp3.internal.connection.RealCall")) {
                    val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
                    runCatching {
                        for (method in cls.declaredMethods) {
                            if (method.name != "execute" && method.name != "enqueue") continue
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                    val key = "net RealCall#${method.name}"
                                    val n = fired.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                                    if (n <= 2) addSample("NET[$n] $key ok")
                                }
                            })
                        }
                    }
                }

                val names = mutableListOf<String>()
                @Suppress("DEPRECATION")
                val dex = DexFile(context.applicationInfo.sourceDir)
                try {
                    dex.entries().asSequence().filter { it.startsWith("com.fasterxml.jackson.core.") }.forEach(names::add)
                } finally {
                    dex.close()
                }
                addSample("jackson namespace classes: ${names.size}")
                var hooked = 0
                for (name in names) {
                    val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
                    runCatching {
                        for (method in cls.declaredMethods) {
                            val ret = method.returnType.name
                            val isFactory = ret.startsWith("com.fasterxml.jackson.core.") && method.parameterTypes.isNotEmpty()
                            // nextToken-shaped: any method returning the token enum type.
                            val isToken = ret == "com.fasterxml.jackson.core.j"
                            if (!isFactory && !isToken) continue
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                    val key = "${cls.name}#${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
                                    val n = fired.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                                    if (n <= 2) {
                                        addSample("FIRED[$n] $key ret=${p.result?.javaClass?.simpleName ?: "null"}")
                                    }
                                }
                            })
                            hooked++
                        }
                    }
                }
                addSample("v13 hooked $hooked factory+token methods")
            }
        }, "XBlocker-probe").apply { isDaemon = true }.start()

        // Periodically fold counters into samples so the report carries usage statistics.
        Thread({
            while (true) {
                Thread.sleep(8000)
                runCatching { snapshotCounters() }
            }
        }, "XBlocker-probe2").apply { isDaemon = true }.start()
    }

    private var lastSnapshot = mapOf<String, Int>()

    private fun snapshotCounters() {
        val current = fired.map { it.key to it.value.get() }.toMap()
        if (current == lastSnapshot) return
        lastSnapshot = current
        val total = current.values.sum()
        val top = current.entries.sortedByDescending { it.value }.take(12)
            .joinToString("; ") { "${it.key.substringAfterLast("com.fasterxml.jackson.core.")}x${it.value}" }
        addSample("usage total=$total :: $top")
    }

    private fun addSample(sample: String) {
        if (samples.size < 60) {
            samples.add(sample.take(900))
            Log.e(TAG, sample.take(300))
        }
    }

    fun drain(): org.json.JSONArray {
        val out = org.json.JSONArray()
        for (s in samples) {
            if (out.length() >= 40) break
            out.put(s)
        }
        return out
    }
}
