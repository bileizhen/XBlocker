package io.github.xblocker.hook

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Small, package-scoped compatibility hooks for ColorOS Fluid Cloud.
 *
 * ColorOS has changed its registration implementation between releases. We
 * therefore only hook the stable permission and whitelist seams that are
 * present in public implementations. The normal promoted notification path
 * remains the source of the actual capsule data.
 */
internal object OplusFluidCloudHook {
    private const val TAG = "XBlocker.FluidCloud"
    private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
    private const val ASSISTANT_PERMISSION = "com.oplus.permission.safe.ASSISTANT"

    val TARGET_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.oplus.systemui.plugins",
        "com.oplus.pantanal.ums",
        "com.coloros.assistantscreen",
    )

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    fun install(packageName: String, classLoader: ClassLoader) {
        when (packageName) {
            "com.android.systemui", "com.oplus.systemui.plugins" -> hookMediaWhitelist(classLoader)
            "com.oplus.pantanal.ums", "com.coloros.assistantscreen" -> hookAssistantPermission(classLoader)
        }
    }

    private fun hookAssistantPermission(classLoader: ClassLoader) {
        // ContextImpl is used by provider and service processes. ContextWrapper
        // is included for ColorOS wrappers that override the check themselves.
        listOf("android.app.ContextImpl", "android.content.ContextWrapper").forEach { className ->
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            clazz.declaredMethods
                .filter { method ->
                    method.name == "checkCallingPermission" &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java))
                }
                .forEach { method ->
                    if (!hookedMethods.add(method)) return@forEach
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.firstOrNull() != ASSISTANT_PERMISSION) return
                            val context = param.thisObject as? Context ?: return
                            val callingUid = Binder.getCallingUid()
                            if (callingUid == Process.myUid()) return
                            val packages = runCatching {
                                context.packageManager.getPackagesForUid(callingUid).orEmpty()
                            }.getOrDefault(emptyArray())
                            if (MODULE_PACKAGE !in packages) return
                            param.result = PackageManager.PERMISSION_GRANTED
                            XposedBridge.log("$TAG: allowed $ASSISTANT_PERMISSION for $MODULE_PACKAGE")
                        }
                    })
                }
        }
    }

    private fun hookMediaWhitelist(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.oplus.systemui.media.seedling.rus.OplusMediaRusUpdateManager",
            classLoader,
        ) ?: return
        clazz.declaredMethods
            .filter { it.name == "getRusWhiteList" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                if (!hookedMethods.add(method)) return@forEach
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? MutableList<Any?> ?: return
                        if (list.any { it == MODULE_PACKAGE }) return
                        runCatching { list.add(MODULE_PACKAGE) }
                            .onSuccess { XposedBridge.log("$TAG: added XBlocker to Oplus media whitelist") }
                            .onFailure { Log.d(TAG, "media whitelist is immutable", it) }
                    }
                })
            }
    }
}
