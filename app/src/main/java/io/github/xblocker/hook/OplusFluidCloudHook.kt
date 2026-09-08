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
import java.util.concurrent.atomic.AtomicBoolean

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
    /** Holds frontBlackList and sibling RUS lists; obfuscated single-letter name per build. */
    private const val SEEDLING_STATE_CLASS = "com.oplus.systemui.plugins.seedling.state.g"
    /** frontBlackList on current ColorOS builds: entries are exempt from the front-app filter. */
    private const val SEEDLING_FRONT_LIST_FIELD = "b"
    private const val FRONT_EXEMPT_PACKAGE = "com.twitter.android"

    val TARGET_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.oplus.systemui.plugins",
        "com.oplus.pantanal.ums",
        "com.coloros.assistantscreen",
    )

    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val frontExemptionInstalled = AtomicBoolean(false)

    fun install(packageName: String, classLoader: ClassLoader) {
        when (packageName) {
            "com.android.systemui", "com.oplus.systemui.plugins" -> {
                hookMediaWhitelist(classLoader)
                hookPresentation(classLoader)
            }
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

    /**
     * ColorOS's AppFrontInterceptor marks a live alert as "front" when the posting package is
     * the foreground app and is absent from the seedling state's frontBlackList; the card
     * repository then drops it "due to front app" as soon as the expanded card collapses.
     * Our capsule is posted by X itself, so without an exemption it is invisible exactly
     * while the user is inside X. The seedling classes live in a plugin classloader inside
     * the SystemUI process, so wait for the state class to load, then keep X present in the
     * exemption list, re-applying after every config reload.
     */
    private fun hookPresentation(classLoader: ClassLoader) {
        if (!frontExemptionInstalled.compareAndSet(false, true)) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass",
                String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        when (param.args[0]) {
                            SEEDLING_STATE_CLASS -> (param.result as? Class<*>)?.let(::hookStateReload)
                            OplusCapsuleAutoExpandHook.MODEL_CLASS ->
                                (param.result as? Class<*>)?.let(OplusCapsuleAutoExpandHook::install)
                        }
                    }
                },
            )
            // Also cover classes already visible when this process installs its hooks.
            XposedHelpers.findClassIfExists(OplusCapsuleAutoExpandHook.MODEL_CLASS, classLoader)
                ?.let(OplusCapsuleAutoExpandHook::install)
        }.onFailure { XposedBridge.log("$TAG: front exemption hook failed: ${it.javaClass.simpleName}") }
    }

    private fun hookStateReload(clazz: Class<*>) {
        val initializers = clazz.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java)) && it.returnType == Void.TYPE
        }
        for (method in initializers) {
            if (!hookedMethods.add(method)) continue
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = exemptFrontPackage(clazz, param.thisObject)
            })
        }
        XposedBridge.log("$TAG: watching seedling front exemption list (${initializers.size} reload methods)")
    }

    private fun exemptFrontPackage(clazz: Class<*>, instance: Any) {
        runCatching {
            val field = clazz.getDeclaredField(SEEDLING_FRONT_LIST_FIELD)
            field.isAccessible = true
            val current = field.get(instance) as? Array<String?>
            if (current != null && FRONT_EXEMPT_PACKAGE in current) return
            field.set(instance, (current?.toList() ?: emptyList()).plus(FRONT_EXEMPT_PACKAGE).toTypedArray())
            XposedBridge.log("$TAG: exempted $FRONT_EXEMPT_PACKAGE from the front-app capsule filter")
        }.onFailure { XposedBridge.log("$TAG: front exemption update failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }
    }
}
