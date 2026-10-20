package io.github.xblocker.hook

import android.content.Context
import android.os.SystemClock
import io.github.xblocker.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class SspAdTargets(
    val policies: List<String>, val gates: List<String>, val disabledEnum: String,
    val slotClass: String?, val stateConstructors: List<String>, val toImmutable: String?, val slotIdMethod: String?,
) {
    fun capabilities() = linkedMapOf("policy" to policies.isNotEmpty(), "gate" to gates.isNotEmpty(),
        "slots" to (slotClass != null && stateConstructors.isNotEmpty() && toImmutable != null && slotIdMethod != null))
    fun toJson() = JSONObject().put("policies", JSONArray(policies)).put("gates", JSONArray(gates))
        .put("disabledEnum", disabledEnum).put("slotClass", slotClass)
        .put("stateConstructors", JSONArray(stateConstructors)).put("toImmutable", toImmutable).put("slotIdMethod", slotIdMethod)
    fun validate(loader: ClassLoader) {
        val type = loader.loadClass(disabledEnum)
        check(type.isEnum && checkNotNull(type.enumConstants).any { (it as Enum<*>).name == "Disabled" })
        policies.forEach { descriptor -> DexMethod(descriptor).getMethodInstance(loader).also {
            check(it.parameterCount == 1 && it.returnType == type && !Modifier.isStatic(it.modifiers))
        } }
        gates.forEach { descriptor -> check(DexMethod(descriptor).getMethodInstance(loader).returnType == Boolean::class.javaPrimitiveType) }
        slotClass?.let(loader::loadClass)
        stateConstructors.forEach { descriptor -> DexMethod(descriptor).getConstructorInstance(loader).also {
            check(it.parameterCount == 5 && List::class.java.isAssignableFrom(it.parameterTypes[1]))
        } }
        toImmutable?.let { DexMethod(it).getMethodInstance(loader).also { method ->
            check(Modifier.isStatic(method.modifiers) && method.parameterTypes.contentEquals(arrayOf(Iterable::class.java)))
            stateConstructors.forEach { descriptor ->
                check(DexMethod(descriptor).getConstructorInstance(loader).parameterTypes[1] == method.returnType)
            }
        } }
        slotIdMethod?.let { DexMethod(it).getMethodInstance(loader).also { method ->
            check(method.returnType == String::class.java && method.parameterCount == 0 && method.declaringClass.name == slotClass)
        } }
    }
    companion object {
        fun fromJson(value: JSONObject): SspAdTargets {
            fun list(key: String) = value.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            fun optional(key: String) = value.optString(key).takeUnless { it.isBlank() || it == "null" }
            return SspAdTargets(list("policies"), list("gates"), value.getString("disabledEnum"),
                optional("slotClass"), list("stateConstructors"), optional("toImmutable"), optional("slotIdMethod"))
        }
    }
}

internal data class SspAdResolution(val targets: SspAdTargets?, val report: JSONObject)

/** Finds the host's SSP-only disable path; never modifies general subscription or player state. */
internal object SspAdLocator {
    private const val SCHEMA = 1
    private const val PREFS = "xblocker_ssp_dexkit"
    private const val ENABLED = "android_x_lite_ssp_ads_enabled"

    @Synchronized fun resolve(context: Context, loader: ClassLoader? = null, force: Boolean = false): SspAdResolution {
        val started = SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val identity = identity(context)
            val fingerprint = identity.first
            if (!force) runCatching {
                val saved = JSONObject(prefs.getString("targets", "{}")!!)
                if (saved.optString("fingerprint") == fingerprint) {
                    val targets = SspAdTargets.fromJson(saved.getJSONObject("targets"))
                    loader?.let(targets::validate)
                    return SspAdResolution(targets, report(targets, "cache", started))
                }
            }.onFailure { prefs.edit().remove("targets").apply() }
            DexKitLocator.loadNative(context)
            val bridges = mutableListOf<DexKitBridge>()
            val targets = try {
                identity.second.filter { path -> ZipFile(path).use { zip -> zip.entries().asSequence().any {
                    Regex("classes(?:[0-9]+)?\\.dex").matches(it.name)
                } } }.forEach { bridges += DexKitBridge.create(it) }
                discover(bridges)
            } finally { bridges.asReversed().forEach { it.close() } }
            check(identity(context).first == fingerprint) { "Host replaced during SSP scan" }
            loader?.let(targets::validate)
            prefs.edit().putString("targets", JSONObject().put("fingerprint", fingerprint).put("targets", targets.toJson()).toString()).apply()
            SspAdResolution(targets, report(targets, "dexkit", started))
        } catch (error: Throwable) {
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            prefs.edit().remove("targets").apply()
            SspAdResolution(null, JSONObject().put("mode", "failed").put("total", 3).put("success", 0)
                .put("details", error.javaClass.simpleName).put("durationMs", SystemClock.elapsedRealtime() - started))
        }
    }

    private fun discover(bridges: List<DexKitBridge>): SspAdTargets {
        val enums = bridges.flatMap { bridge -> bridge.findMethod { matcher {
            name = "<clinit>"; usingStrings(listOf("Disabled", "ClientDriven", "ServerSlotClientFetch", "ServerToServer"), StringMatchType.Equals)
        } } }.map { it.declaredClassName }.distinct()
        val disabledEnum = checkNotNull(enums.singleOrNull()) { "SSP enum not unique" }
        val anchored = bridges.flatMap { bridge -> bridge.findMethod { matcher {
            usingStrings(listOf(ENABLED), StringMatchType.Equals)
        } } }.distinctBy { it.descriptor }
        val policies = anchored.filter { it.returnTypeName == disabledEnum && it.paramCount == 1 && !Modifier.isStatic(it.modifiers) }
        check(policies.isNotEmpty()) { "No SSP policy" }
        val gates = (anchored.filter { it.returnTypeName == "boolean" && it.paramCount == 0 } +
            policies.flatMap { policy -> policy.callers.filter { it.declaredClassName == policy.declaredClassName &&
                it.returnTypeName == "boolean" && it.paramTypeNames == policy.paramTypeNames } })
            .filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.distinct()
        val slots = bridges.flatMap { bridge -> bridge.findMethod { matcher {
            name = "toString"; paramCount = 0
            usingStrings(listOf("UrtTimelineRtbImageAd(advertiserAvatarImageUrl="), StringMatchType.Equals)
        } } }.map { it.declaredClassName }.distinct()
        val slotClass = slots.singleOrNull()
        val states = bridges.flatMap { bridge -> bridge.findMethod { matcher {
            name = "toString"; paramCount = 0
            usingStrings(listOf("Success(timelineType=", ", timelineItems=", ", refreshState="), StringMatchType.Equals)
        } } }.mapNotNull { it.declaredClass }.distinctBy { it.name }
        val stateCtors = states.singleOrNull()?.methods.orEmpty().filter { it.isConstructor && it.paramCount == 5 &&
            it.paramTypeNames.takeLast(2) == listOf("boolean", "boolean") }.let { listOfNotNull(it.singleOrNull()) }
        val listType = stateCtors.map { it.paramTypeNames[1] }.distinct().singleOrNull()
        val immutable = listType?.let { type -> bridges.flatMap { bridge -> bridge.findMethod { matcher {
            paramTypes(listOf("java.lang.Iterable")); returnType = type
        } } }.filter { Modifier.isStatic(it.modifiers) }.distinctBy { it.descriptor }.singleOrNull()?.descriptor }
        // The proven SSP item implements entry identity with one String getter, besides toString.
        // An additional getter in a future model makes this ambiguous and disables the slot path.
        val slotId = slots.singleOrNull()?.let { type -> bridges.flatMap { bridge -> bridge.findMethod { matcher {
            declaredClass = type; paramCount = 0; returnType = "java.lang.String"
        } } }.filter { it.name != "toString" && !Modifier.isStatic(it.modifiers) }
            .distinctBy { it.descriptor }.singleOrNull()?.descriptor }
        return SspAdTargets(policies.map { it.descriptor }, gates, disabledEnum, slotClass,
            stateCtors.map { it.descriptor }.distinct(), immutable, slotId)
    }

    private fun identity(context: Context): Pair<String, List<String>> {
        val host = context.packageManager.getPackageInfo("com.twitter.android", 0)
        val info = checkNotNull(host.applicationInfo)
        val paths = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).distinct().sorted()
        val text = buildString {
            append("$SCHEMA:${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}:${host.longVersionCode}:${host.lastUpdateTime}")
            paths.forEach { path -> val f = File(path); check(f.isFile && f.canRead()); append('\n').append(f.canonicalPath).append(':').append(f.length()).append(':').append(f.lastModified()) }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } to paths
    }

    private fun report(targets: SspAdTargets, mode: String, started: Long): JSONObject {
        val items = targets.capabilities()
        return JSONObject().put("mode", mode).put("total", items.size).put("success", items.values.count { it })
            .put("items", JSONObject(items)).put("details", if (items.values.all { it }) "matched" else "partial")
            .put("durationMs", SystemClock.elapsedRealtime() - started)
    }
}
