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
 * Only requests identifiable as XBlocker's own, or as any package carrying this
 * module's focus payload, are allowed; unrecognised signatures keep the OEM result.
 */
internal object XiaomiFocusHook {
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
    // The live focus notification is published from X's process, so eligibility checks
    // see X's package; the payload's business id is what proves the request is ours.
    private const val X_PACKAGE = "com.twitter.android"
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
                val xUnmatched = AtomicBoolean()
                hookOnce(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Never infer notification ownership from the SystemUI context.
                        val reason = allowed(param.args)
                        if (reason != null) {
                            param.result = true
                            if (reported.compareAndSet(false, true)) log("allowed XBlocker: ${method.name} ($reason)")
                        } else if (referencesX(param.args) && xUnmatched.compareAndSet(false, true)) {
                            // An X-owned check our rules did not cover — likely a call form
                            // carrying only the package string. Recorded to guide a wider rule.
                            log("X request without module payload: ${method.name}")
                        }
                    }
                })
            }
        }
    }

    /**
     * Returns why the check may be allowed, or null to keep the OEM result:
     * 1. any argument identifying XBlocker — the module-owned fallback route and
     *    signature comparisons that name the module;
     * 2. a notification of any package carrying this module's focus payload — the
     *    X-owned live route, whose posting package is com.twitter.android.
     */
    private fun allowed(args: Array<Any?>): String? {
        for (value in args) {
            if (value is StatusBarNotification) {
                if (value.packageName == MODULE_PACKAGE) return "module-package"
                if (carriesOurPayload(value)) return "focus-payload@${value.packageName}"
            } else if (identityOf(value) == MODULE_PACKAGE) return "module-package"
        }
        return null
    }

    private fun carriesOurPayload(sbn: StatusBarNotification): Boolean =
        io.github.xblocker.fluid.XiaomiFocusPayload.owns(
            sbn.notification?.extras?.getString(io.github.xblocker.fluid.XiaomiFocusPayload.PARAM_KEY))

    private fun referencesX(args: Array<Any?>): Boolean = args.any {
        (it as? StatusBarNotification)?.packageName == X_PACKAGE || identityOf(it) == X_PACKAGE
    }

    private fun identityOf(value: Any?): String? = when (value) {
        is String -> value
        is ApplicationInfo -> value.packageName
        is PackageInfo -> value.packageName
        else -> null
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
