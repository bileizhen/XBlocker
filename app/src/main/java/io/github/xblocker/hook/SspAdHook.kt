package io.github.xblocker.hook

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.wrap.DexMethod
import java.util.concurrent.atomic.AtomicLong

/** SSP-only policy and timeline-state filtering. Never hides a View or changes player navigation. */
internal class SspAdHook(
    private val context: Context, private val loader: ClassLoader,
    private val enabled: () -> Boolean, private val onBlocked: (String) -> Unit,
) {
    @Volatile var status = "scanning"
        private set
    @Volatile var scanReport: JSONObject? = null
        private set
    val policyCalls = AtomicLong()
    val slots = AtomicLong()
    val blocked = AtomicLong()
    private val ledger = SspSlotLedger()
    private var logged = false

    fun install() {
        val resolution = SspAdLocator.resolve(context, loader)
        scanReport = resolution.report
        val targets = resolution.targets ?: run { status = "scan_failed"; return }
        val disabled = checkNotNull(loader.loadClass(targets.disabledEnum).enumConstants).single { (it as Enum<*>).name == "Disabled" }
        var installed = 0
        var failed = false
        fun installOne(action: () -> Unit) {
            runCatching { action(); installed++ }.onFailure { failed = true; logOnce(it) }
        }
        targets.policies.forEach { descriptor -> installOne {
            XposedBridge.hookMethod(DexMethod(descriptor).getMethodInstance(loader), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) { policyCalls.incrementAndGet(); param.result = disabled }
                }
            })
        } }
        targets.gates.forEach { descriptor -> installOne {
            XposedBridge.hookMethod(DexMethod(descriptor).getMethodInstance(loader), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) { policyCalls.incrementAndGet(); param.result = false }
                }
            })
        } }
        if (targets.capabilities()["slots"] == true) {
            val slot = loader.loadClass(checkNotNull(targets.slotClass))
            val id = DexMethod(checkNotNull(targets.slotIdMethod)).getMethodInstance(loader).apply { isAccessible = true }
            val immutable = DexMethod(checkNotNull(targets.toImmutable)).getMethodInstance(loader).apply { isAccessible = true }
            targets.stateConstructors.forEach { descriptor -> installOne {
                val constructor = DexMethod(descriptor).getConstructorInstance(loader)
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        runCatching {
                            val original = param.args[1] as? List<*> ?: return
                            val removed = ArrayList<Any>()
                            val filtered = SspSlotPolicy.remove(original, true) { value ->
                                (value != null && slot.isInstance(value)).also { if (it) removed += checkNotNull(value) }
                            }
                            if (filtered === original) return
                            val replacement = immutable.invoke(null, filtered)
                            check(constructor.parameterTypes[1].isInstance(replacement))
                            param.args[1] = replacement
                            slots.addAndGet(removed.size.toLong())
                            removed.forEach { value ->
                                val key = (id.invoke(value) as? String)?.take(300).orEmpty()
                                if (ledger.record(key)) { blocked.incrementAndGet(); onBlocked(key) }
                            }
                        }.onFailure(::logOnce)
                    }
                })
            } }
        }
        status = if (failed || targets.capabilities().values.any { !it }) "partial:$installed/${resolution.report.optString("mode")}" else
            "active:$installed/${resolution.report.optString("mode")}"
    }

    private fun logOnce(error: Throwable) {
        if (!logged) { logged = true; XposedBridge.log("XBlocker.SSP: skipped ${error.javaClass.simpleName}") }
    }
}
