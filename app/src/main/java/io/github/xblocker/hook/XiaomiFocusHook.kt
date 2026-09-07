package io.github.xblocker.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Focus whitelist/signature entry points identified by HyperIsland (MIT, 1812z).
 * See assets/licenses/HyperIsland.txt and docs/xiaomi-super-island.md.
 * Only XBlocker's package is allowed; unrecognised signatures keep the OEM result.
 */
internal object XiaomiFocusHook {
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
    private val hooked = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    fun install(loader: ClassLoader) {
        // SystemUI is also a recommended scope on other brands: absent Xiaomi classes
        // simply do nothing. Always watch plugins, even if one class was found directly.
        installChecks(loader)
        val factory = XposedHelpers.findClassIfExists(
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory", loader,
        ) ?: return
        factory.declaredMethods.filter {
            it.name == "createPluginContext" && Context::class.java.isAssignableFrom(it.returnType)
        }.forEach { method ->
            hookOnce(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.result as? Context ?: return
                    runCatching { installChecks(context.classLoader) }
                        .onFailure { log("plugin inspection failed: ${it.javaClass.simpleName}") }
                }
            })
        }
    }

    private fun installChecks(loader: ClassLoader) {
        val targets = mapOf(
            "miui.systemui.notification.NotificationSettingsManager" to setOf("canShowFocus", "canCustomFocus"),
            "miui.systemui.notification.focus.SignatureChecker" to setOf("checkSignatures"),
        )
        for ((name, methods) in targets) {
            val target = XposedHelpers.findClassIfExists(name, loader) ?: continue
            target.declaredMethods.filter {
                it.name in methods && it.returnType == Boolean::class.javaPrimitiveType
            }.forEach { method ->
                val reported = AtomicBoolean()
                hookOnce(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Never infer notification ownership from the SystemUI context.
                        if (param.args.any { ownsNotification(it) }) {
                            param.result = true
                            if (reported.compareAndSet(false, true)) log("allowed XBlocker: ${method.name}")
                        }
                    }
                })
            }
        }
    }

    private fun ownsNotification(value: Any?): Boolean = when (value) {
        is String -> value == MODULE_PACKAGE
        is StatusBarNotification -> value.packageName == MODULE_PACKAGE
        is ApplicationInfo -> value.packageName == MODULE_PACKAGE
        is PackageInfo -> value.packageName == MODULE_PACKAGE
        else -> false
    }

    private fun hookOnce(method: Method, callback: XC_MethodHook) {
        if (!hooked.add(method)) return
        runCatching { XposedBridge.hookMethod(method, callback) }
            .onSuccess { log("hooked ${method.declaringClass.name}.${method.name}") }
            .onFailure {
                hooked.remove(method)
                log("hook unavailable: ${method.name}: ${it.javaClass.simpleName}")
            }
    }

    private fun log(message: String) = XposedBridge.log("XBlocker.Focus: $message")
}
