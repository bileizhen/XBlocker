package io.github.xblocker.hook

import android.content.Context
import android.os.SystemClock
import io.github.xblocker.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class MediaMenuTargets(
    val models: MediaModelSchema,
    val videoMenus: List<String>,
    val photoSheet: String?,
    val photoPopup: String?,
    val sheetRender: String?,
    val itemConstructor: String?,
    val itemIdField: String?,
    val toImmutable: String?,
    val iconConstructor: String?,
    val popupRow: String?,
    val photoDownloadEnum: String?,
    val legacyRows: List<String> = emptyList(),
    val downloadIconField: String? = null,
) {
    fun toJson() = JSONObject().put("models", models.toJson()).put("videoMenus", JSONArray(videoMenus))
        .put("photoSheet", photoSheet).put("photoPopup", photoPopup).put("sheetRender", sheetRender)
        .put("itemConstructor", itemConstructor).put("itemIdField", itemIdField).put("toImmutable", toImmutable)
        .put("iconConstructor", iconConstructor).put("popupRow", popupRow).put("photoDownloadEnum", photoDownloadEnum).put("legacyRows", JSONArray(legacyRows))
        .put("downloadIconField", downloadIconField)
    fun capabilities() = linkedMapOf(
        "videoMenus" to (models.videoClasses.isNotEmpty() && models.variantClasses.isNotEmpty() && videoMenus.isNotEmpty() && sheetReady),
        "photoMenus" to (models.imageClass != null && (photoSheet != null && sheetReady || photoPopup != null || legacyRows.isNotEmpty())),
        "sheetItems" to (itemConstructor != null && itemIdField != null && toImmutable != null),
        "sheetRender" to (sheetRender != null),
        "popupRow" to (popupRow != null || legacyRows.isNotEmpty()),
        "mediaModels" to models.modelClasses.isNotEmpty(),
    )
    val sheetReady get() = sheetRender != null && itemConstructor != null && itemIdField != null && toImmutable != null
    fun validate(loader: ClassLoader) {
        videoMenus.forEach { DexMethod(it).getMethodInstance(loader) }
        (listOfNotNull(photoSheet, photoPopup, sheetRender, toImmutable, popupRow) + legacyRows).forEach { DexMethod(it).getMethodInstance(loader) }
        listOfNotNull(itemConstructor, iconConstructor).forEach { DexMethod(it).getConstructorInstance(loader) }
        itemIdField?.let { DexField(it).getFieldInstance(loader) }
        downloadIconField?.let { DexField(it).getFieldInstance(loader) }
        models.modelClasses.forEach { loader.loadClass(it) }
        (models.models + models.variants).forEach { spec -> spec.fields.forEach { f ->
            check(loader.loadClass(f.owner).getDeclaredField(f.name).type.typeName == f.type) { "Cached media field changed" }
        } }
    }
    companion object {
        fun fromJson(j: JSONObject): MediaMenuTargets {
            fun s(key: String) = j.optString(key).takeIf { it.isNotEmpty() && it != "null" }
            return MediaMenuTargets(MediaModelSchema.fromJson(j.getJSONObject("models")),
                j.getJSONArray("videoMenus").let { a -> List(a.length()) { a.getString(it) } },
                s("photoSheet"), s("photoPopup"), s("sheetRender"), s("itemConstructor"), s("itemIdField"),
                s("toImmutable"), s("iconConstructor"), s("popupRow"), s("photoDownloadEnum"),
                j.optJSONArray("legacyRows")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), s("downloadIconField"))
        }
    }
}

internal data class MediaMenuResolution(val targets: MediaMenuTargets?, val report: JSONObject)

/** Discovers behavior and shapes, not obfuscated names or a host-version allowlist. */
internal object MediaMenuLocator {
    private const val SCHEMA = 2
    private const val PREFS = "xblocker_media_dexkit"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val MODIFIER = "androidx.compose.ui.Modifier"
    private const val F0 = "kotlin.jvm.functions.Function0"
    private const val F1 = "kotlin.jvm.functions.Function1"

    @Synchronized fun resolve(context: Context, loader: ClassLoader? = null, force: Boolean = false): MediaMenuResolution {
        val host = context.packageManager.getPackageInfo("com.twitter.android", 0)
        val info = requireNotNull(host.applicationInfo)
        return discover(context, (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).distinct().sorted(),
            "${host.longVersionCode}:${host.lastUpdateTime}", loader, force)
    }

    /** Artifact-only acceptance check: scans exactly the supplied files, does not hook or launch X. */
    @Synchronized fun inspectApk(context: Context, path: String): MediaMenuResolution = discover(context,
        listOf(File(path).canonicalPath), "artifact", null, true, cache = false)

    private fun discover(context: Context, paths: List<String>, hostIdentity: String, loader: ClassLoader?, force: Boolean,
                         cache: Boolean = true): MediaMenuResolution {
        val started = SystemClock.elapsedRealtime()
        val fingerprint = fingerprint(paths, hostIdentity)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            if (cache && !force) runCatching {
                val saved = JSONObject(prefs.getString("targets", "{}")!!)
                if (saved.optString("fingerprint") == fingerprint) {
                    val targets = MediaMenuTargets.fromJson(saved.getJSONObject("targets"))
                    loader?.let(targets::validate)
                    return MediaMenuResolution(targets, report(targets, "cache", started, fingerprint))
                }
            }.onFailure { prefs.edit().remove("targets").apply() }
            DexKitLocator.loadNative(context)
            val bridges = mutableListOf<DexKitBridge>()
            val targets = try {
                paths.filter { path -> ZipFile(path).use { z -> z.entries().asSequence().any { Regex("classes(?:[0-9]+)?\\.dex").matches(it.name) } } }
                    .forEach { bridges += DexKitBridge.create(it) }
                check(bridges.isNotEmpty()) { "No DEX" }
                discoverTargets(bridges)
            } finally { bridges.asReversed().forEach { it.close() } }
            check(fingerprint == fingerprint(paths, hostIdentity)) { "APK changed during media scan" }
            if (cache) {
                val latest = context.packageManager.getPackageInfo("com.twitter.android", 0)
                val latestInfo = requireNotNull(latest.applicationInfo)
                val latestPaths = (listOf(latestInfo.sourceDir) + latestInfo.splitSourceDirs.orEmpty()).distinct().sorted()
                check(fingerprint == fingerprint(latestPaths, "${latest.longVersionCode}:${latest.lastUpdateTime}")) { "Host replaced during media scan" }
            }
            loader?.let(targets::validate)
            if (cache) prefs.edit().putString("targets", JSONObject().put("fingerprint", fingerprint).put("targets", targets.toJson()).toString()).apply()
            return MediaMenuResolution(targets, report(targets, "dexkit", started, fingerprint))
        } catch (e: Throwable) {
            if (e is ThreadDeath || e is VirtualMachineError) throw e
            if (cache) prefs.edit().remove("targets").apply()
            return MediaMenuResolution(null, JSONObject().put("mode", "failed").put("total", 6).put("success", 0)
                .put("durationMs", SystemClock.elapsedRealtime() - started).put("details", e.message?.take(160) ?: e.javaClass.simpleName))
        }
    }

    private fun discoverTargets(bridges: List<DexKitBridge>): MediaMenuTargets {
        fun methods(params: List<String?>, result: String = "void"): List<MethodData> = bridges.flatMap { b ->
            b.findMethod { matcher { paramTypes(params); returnType = result } }
        }.filter { Modifier.isStatic(it.modifiers) }.distinctBy { it.descriptor }
        fun anchored(prefix: String) = bridges.flatMap { b -> b.findMethod {
            matcher { name = "toString"; paramCount = 0; returnType = "java.lang.String"; usingStrings(listOf(prefix), StringMatchType.StartsWith) }
        } }.distinctBy { it.descriptor }
        fun List<MethodData>.unique() = singleOrNull()?.descriptor

        val models = MediaModelDiscovery.discover(bridges)
        val videoStates = anchored("ShowVideoOptions(").map { it.declaredClassName }.distinct()
        val videos = videoStates.mapNotNull { methods(listOf(it, F0, F1, COMPOSER, "int")).singleOrNull() }.map { it.descriptor }.distinct()
        val item = anchored("ActionSheetItem(id=").singleOrNull()?.declaredClass
        val constructors = item?.methods.orEmpty().filter { it.isConstructor && it.paramCount == 6 &&
            it.paramTypeNames.let { p -> p[0] == "java.lang.Object" && p[2] == "java.lang.String" && p[3] == "java.lang.String" && p[4] == "java.lang.Boolean" && p[5] == "int" } }
        val ctor = constructors.singleOrNull()
        val id = item?.fields?.filter { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object" }?.singleOrNull()?.descriptor
        val icon = ctor?.paramTypes?.get(1)
        val iconCtor = icon?.methods?.filter { it.isConstructor && it.paramTypeNames == listOf("int", "boolean") }?.singleOrNull()?.descriptor
        val render = methods(listOf(null, F1, MODIFIER, "java.lang.String", "java.lang.String", COMPOSER, "int", "int")).singleOrNull()
        val immutableType = render?.paramTypeNames?.firstOrNull()
        val immutable = immutableType?.let { methods(listOf("java.lang.Iterable"), it).unique() }
        val photos = methods(listOf("java.util.List", F1, F0, COMPOSER, "int")).filter { m ->
            m.declaredClass?.methods?.any { candidate -> candidate.paramTypeNames.any { it in models.modelClasses } } == true
        }
        val photoSheet = photos.unique()
        val galleryOwners = photos.map { it.declaredClassName }.distinct()
        val popup = methods(listOf("boolean", F0, F1, "java.util.List", COMPOSER, "int")).filter { it.declaredClassName in galleryOwners }.unique()
        val enums = bridges.flatMap { b -> b.findMethod { matcher {
            name = "<clinit>"; usingStrings(listOf("Download", "ViewIn4k"), StringMatchType.Equals)
        } } }.map { it.declaredClassName }.distinct().singleOrNull()
        // Photo action callbacks bind (onAction, Download/ViewIn4k enum, dismiss). Their renderer
        // supplies X's own download icon; never assume an obfuscated global field name or resource ID.
        val downloadIcon = if (enums != null && icon != null) bridges.flatMap { b -> b.findMethod { matcher {
            name = "<init>"; paramTypes(listOf(F1, enums, F0, null))
        } } }.flatMap { it.callers }.distinctBy { it.descriptor }.flatMap { method ->
            method.usingFields.map { it.field }.filter { Modifier.isStatic(it.modifiers) && it.typeName == icon.name }
        }.distinctBy { it.descriptor }.singleOrNull()?.descriptor else null
        val row = if (icon != null) methods(listOf("java.lang.String", F0, MODIFIER, icon.name, null,
            "boolean", "boolean", "boolean", null, COMPOSER, "int", "int")).unique() else null
        val legacyRows = if (popup == null) methods(listOf("kotlin.jvm.functions.Function2", F0, MODIFIER,
            "kotlin.jvm.functions.Function2", null, "boolean", null, null, COMPOSER, "int", "int"))
            .filter { it.declaredClassName.startsWith("androidx.compose.material3.") }.map { it.descriptor } else emptyList()
        return MediaMenuTargets(models, videos, photoSheet, popup, render?.descriptor, ctor?.descriptor, id, immutable, iconCtor, row, enums, legacyRows, downloadIcon)
    }

    private fun fingerprint(paths: List<String>, host: String): String {
        val text = buildString {
            append("$SCHEMA:${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}:$host")
            paths.forEach { path -> val f = File(path); check(f.isFile && f.canRead()); append('\n').append(f.canonicalPath).append(':').append(f.length()).append(':').append(f.lastModified()) }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun report(t: MediaMenuTargets, mode: String, started: Long, fingerprint: String): JSONObject {
        val capabilities = t.capabilities()
        return JSONObject().put("mode", mode).put("total", capabilities.size).put("success", capabilities.values.count { it })
            .put("durationMs", SystemClock.elapsedRealtime() - started).put("fingerprint", fingerprint)
            .put("items", JSONObject(capabilities)).put("videoEntryCount", t.videoMenus.size)
            .put("modelCount", t.models.modelClasses.size).put("details", if (capabilities.values.all { it }) "matched" else "partial")
            .put("nativeDownloadIcon", t.downloadIconField != null)
    }
}
