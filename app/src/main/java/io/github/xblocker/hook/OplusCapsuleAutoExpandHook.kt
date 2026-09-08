package io.github.xblocker.hook

import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.xblocker.fluid.OplusCapsulePolicy
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** ColorOS 16.1 automatic reminder decision; manual card expansion uses a separate path. */
internal object OplusCapsuleAutoExpandHook {
    const val MODEL_CLASS = "o5.j"
    private const val TAG = "XBlocker.FluidCloud"
    private val installed = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    fun install(clazz: Class<*>) {
        if (!installed.add(clazz)) return
        runCatching {
            // Fingerprint the inspected plugin layout before hooking an obfuscated method.
            val loader = checkNotNull(clazz.classLoader)
            check(loader.loadClass("com.oplus.systemui.plugins.seedling.state.g").classLoader == loader)
            val base = clazz.getDeclaredField("a").apply { isAccessible = true }
            check(base.type.name == "p4.o")
            check(!Modifier.isStatic(base.modifiers))
            val automaticReminder = clazz.getDeclaredMethod("L")
            check(automaticReminder.returnType == Boolean::class.javaPrimitiveType)
            check(!Modifier.isStatic(automaticReminder.modifiers))
            check(clazz.getDeclaredMethod("getKey").returnType == String::class.java)
            check(clazz.getDeclaredMethod("f").returnType == String::class.java)
            val logged = AtomicBoolean()
            val failed = AtomicBoolean()
            XposedBridge.hookMethod(automaticReminder, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val source = base.get(param.thisObject) ?: return
                        // Only notification-backed models have this field type. UPK/service
                        // models and unrelated X notifications retain the system decision.
                        val notificationField = source.javaClass.declaredFields.singleOrNull {
                            it.type == StatusBarNotification::class.java && !Modifier.isStatic(it.modifiers)
                        } ?: return
                        notificationField.isAccessible = true
                        val sbn = notificationField.get(source) as? StatusBarNotification ?: return
                        if (!OplusCapsulePolicy.suppressAutomaticExpansion(
                                sbn.packageName, sbn.id, sbn.notification.channelId)) return
                        // CardsRepository passes this result to the automatic card list.
                        // Its manual list does not consult this predicate, so tapping the
                        // capsule can still expand it. Apply on every report/re-entry.
                        param.result = false
                        if (logged.compareAndSet(false, true)) {
                            XposedBridge.log("$TAG: default collapsed status capsule (${sbn.packageName})")
                        }
                    }.onFailure {
                        if (failed.compareAndSet(false, true)) {
                            XposedBridge.log("$TAG: collapsed status lookup unavailable: ${it.javaClass.simpleName}")
                        }
                    }
                }
            })
            XposedBridge.log("$TAG: hooked automatic capsule reminder (${clazz.name}.L)")
        }.onFailure {
            installed.remove(clazz)
            XposedBridge.log("$TAG: automatic capsule reminder hook unavailable: ${it.javaClass.simpleName}")
        }
    }
}
