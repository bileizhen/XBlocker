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

/** Header action row and hosted media-timeline operation used by profile "download all". */
internal data class ProfileMediaTargets(
    val rows: List<String>,
    val button: String,
    val buttonConstructor: String,
    val immutableList: String,
    val operation: String,
    val operationName: String,
    // Follow/subscribe button rendered after the phone pill row, plus the pill renderer X uses
    // for ProfilePrimaryActionButton items. Used to append the download pill at the far right.
    val follow: String? = null,
    val renderer: String? = null,
    // Empty Modifier singleton (Modifier.Companion, merged by R8) used by the row's own pills.
    val emptyModifier: String? = null,
    // Overflow (⋯) menu: the profile menu item model (ProfileMenuItem), the model holding the
    // menu list, and the composable that renders that model with its action callbacks.
    val menuItem: String? = null,
    val menuItemConstructor: String? = null,
    val menuModel: String? = null,
    val menuRenderer: String? = null,
    // X's own download icon (shared with the media menus), used for the two menu entries.
    val menuIcon: String? = null,
) {
    fun toJson() = JSONObject().put("rows", JSONArray(rows)).put("button", button).put("buttonConstructor", buttonConstructor)
        .put("immutableList", immutableList).put("operation", operation).put("operationName", operationName)
        .put("follow", follow).put("renderer", renderer).put("emptyModifier", emptyModifier)
        .put("menuItem", menuItem).put("menuItemConstructor", menuItemConstructor)
        .put("menuModel", menuModel).put("menuRenderer", menuRenderer).put("menuIcon", menuIcon)
    fun validate(loader: ClassLoader) {
        rows.forEach { DexMethod(it).getMethodInstance(loader) }
        DexMethod(buttonConstructor).getConstructorInstance(loader)
        DexMethod(immutableList).getMethodInstance(loader)
        loader.loadClass(operation).getMethod(operationName)
        follow?.let { DexMethod(it).getMethodInstance(loader) }
        renderer?.let { DexMethod(it).getMethodInstance(loader) }
        emptyModifier?.let { DexField(it).getFieldInstance(loader) }
        menuItem?.let { loader.loadClass(it) }
        menuItemConstructor?.let { DexMethod(it).getConstructorInstance(loader) }
        menuModel?.let { loader.loadClass(it) }
        menuRenderer?.let { DexMethod(it).getMethodInstance(loader) }
        menuIcon?.let { DexField(it).getFieldInstance(loader) }
    }
    companion object {
        fun fromJson(j: JSONObject): ProfileMediaTargets? = runCatching {
            fun s(key: String) = j.getString(key)
            fun o(key: String) = j.optString(key).takeIf { it.isNotEmpty() && it != "null" }
            val rows = j.getJSONArray("rows").let { a -> List(a.length()) { a.getString(it) } }
            ProfileMediaTargets(rows, s("button"), s("buttonConstructor"), s("immutableList"),
                s("operation"), s("operationName"), o("follow"), o("renderer"), o("emptyModifier"),
                o("menuItem"), o("menuItemConstructor"), o("menuModel"), o("menuRenderer"), o("menuIcon"))
        }.getOrNull()
    }
}

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
    // Immersive player sheet (12.45+): a tray composable plus the enum-row list it renders.
    val videoTrays: List<String> = emptyList(),
    val videoRows: List<String> = emptyList(),
    val profile: ProfileMediaTargets? = null,
) {
    fun toJson() = JSONObject().put("models", models.toJson()).put("videoMenus", JSONArray(videoMenus))
        .put("photoSheet", photoSheet).put("photoPopup", photoPopup).put("sheetRender", sheetRender)
        .put("itemConstructor", itemConstructor).put("itemIdField", itemIdField).put("toImmutable", toImmutable)
        .put("iconConstructor", iconConstructor).put("popupRow", popupRow).put("photoDownloadEnum", photoDownloadEnum).put("legacyRows", JSONArray(legacyRows))
        .put("downloadIconField", downloadIconField)
        .put("videoTrays", JSONArray(videoTrays)).put("videoRows", JSONArray(videoRows))
        .put("profile", profile?.toJson())
    fun capabilities() = linkedMapOf(
        "videoMenus" to (models.videoClasses.isNotEmpty() && models.variantClasses.isNotEmpty() && videoReady),
        "profileDownload" to (profile != null),
        "photoMenus" to (models.imageClass != null && (photoSheet != null && sheetReady || photoPopup != null || legacyRows.isNotEmpty())),
        "sheetItems" to (itemConstructor != null && itemIdField != null && toImmutable != null),
        "sheetRender" to (sheetRender != null),
        "popupRow" to (popupRow != null || legacyRows.isNotEmpty()),
        "mediaModels" to models.modelClasses.isNotEmpty(),
    )
    val sheetReady get() = sheetRender != null && itemConstructor != null && itemIdField != null && toImmutable != null
    val videoReady get() = (videoTrays.isNotEmpty() && videoRows.isNotEmpty()) ||
        (videoMenus.isNotEmpty() && sheetReady)
    fun validate(loader: ClassLoader) {
        videoMenus.forEach { DexMethod(it).getMethodInstance(loader) }
        (videoTrays + videoRows).forEach { DexMethod(it).getMethodInstance(loader) }
        profile?.validate(loader)
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
                j.optJSONArray("legacyRows")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), s("downloadIconField"),
                j.optJSONArray("videoTrays")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                j.optJSONArray("videoRows")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                j.optJSONObject("profile")?.let(ProfileMediaTargets::fromJson))
        }
    }
}

internal data class MediaMenuResolution(val targets: MediaMenuTargets?, val report: JSONObject)

/** Discovers behavior and shapes, not obfuscated names or a host-version allowlist. */
internal object MediaMenuLocator {
    private const val SCHEMA = 12
    private const val PREFS = "xblocker_media_dexkit"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val MODIFIER = "androidx.compose.ui.Modifier"
    private const val F0 = "kotlin.jvm.functions.Function0"
    private const val F1 = "kotlin.jvm.functions.Function1"

    @Synchronized fun resolve(context: Context, loader: ClassLoader? = null, force: Boolean = false): MediaMenuResolution {
        val host = context.packageManager.getPackageInfo("com.twitter.android", 0)
        val info = requireNotNull(host.applicationInfo)
        return discover(context, HostApkPaths.resolve(info, loader),
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
                val latestPaths = HostApkPaths.resolve(latestInfo, loader)
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
        // Legacy builds render the video options as an ActionSheet with one onAction callback.
        val videos = videoStates.mapNotNull { methods(listOf(it, F0, F1, COMPOSER, "int")).singleOrNull() }.map { it.descriptor }.distinct()
        // The immersive player sheet passes one dismiss callback plus several per-item callbacks,
        // and renders rows from a state-derived enum list instead of an ActionSheet.
        val immersive = videoStates.mapNotNull { state ->
            val tray = methods(listOf(state, F0, F1, F1, F1, F1, F1, COMPOSER, "int")).singleOrNull()
                ?: return@mapNotNull null
            val row = methods(listOf(state), "java.util.ArrayList").singleOrNull() ?: return@mapNotNull null
            tray.descriptor to row.descriptor
        }
        val trays = immersive.map { it.first }.distinct()
        val rows = immersive.map { it.second }.distinct()
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
        // Profile header "download all": the native action row, its action model, and the hosted
        // UserProfileMediaTimelineQuery operation. Everything is discovered by stable behavior.
        val profileAction = anchored("ProfileMenuItem(text=").singleOrNull()
        val profileActionName = profileAction?.declaredClassName
        val profileRow = if (profileActionName != null) methods(listOf(null, F1, null,
            "kotlinx.collections.immutable.c", MODIFIER, COMPOSER, "int"))
            .filter { method -> method.usingFields.any { it.field.declaredClassName == profileActionName } }
            .singleOrNull() else null
        // The phone layout renders the k0 "ProfilePrimaryActionButton" list through d.e; tablets
        // append the same list to d.h after the large follow button.
        val profileButton = anchored("ProfilePrimaryActionButton(type=").singleOrNull()
        val profileButtonFields = profileButton?.declaredClass?.fields.orEmpty().map { it.descriptor }.toSet()
        val profileListRow = methods(listOf("kotlinx.collections.immutable.c", F1, MODIFIER, null, COMPOSER, "int"))
            .filter { method -> method.usingFields.any { it.field.descriptor in profileButtonFields } }
            .singleOrNull()
        val profileButtonCtor = profileButton?.declaredClass?.methods
            ?.filter { it.isConstructor && it.paramCount == 3 && it.paramTypeNames.getOrNull(1) == "java.lang.String" }
            ?.singleOrNull()
        val profileImmutable = methods(listOf("java.lang.Iterable"), "kotlinx.collections.immutable.c").singleOrNull()
        val profileOperation = bridges.flatMap { bridge -> bridge.findMethod { matcher {
            name = "name"; paramCount = 0; returnType = "java.lang.String"
            usingStrings(listOf("UserProfileMediaTimelineQuery"), StringMatchType.Equals)
        } } }.distinctBy { it.descriptor }.singleOrNull()
        val profileRows = listOfNotNull(profileListRow?.descriptor, profileRow?.descriptor).distinct()
        // The follow/subscribe composable renders after the phone pill row; the pill renderer is
        // the only static (ProfilePrimaryActionButton, Function1, Modifier, Composer, int) method.
        val profileFollow = profileListRow?.let { row ->
            methods(listOf(null, F1, "boolean", MODIFIER, null, "boolean", COMPOSER, "int", "int"))
                .filter { it.declaredClassName == row.declaredClassName }
                .singleOrNull()
        }
        val profileRenderer = profileButton?.let { button ->
            methods(listOf(button.declaredClassName, F1, MODIFIER, COMPOSER, "int")).singleOrNull()
        }
        // The row's own pills start from Modifier.Companion; R8 merges that singleton into a
    // self-typed static field, e.g. androidx.compose.ui.q.a (Modifier). Discover it by shape.
        val profileEmptyModifier = profileListRow?.usingFields?.map { it.field }?.firstOrNull { field ->
            // DexKit reports declaredClassName/typeName in dotted form, typeSign in descriptor form.
            Modifier.isStatic(field.modifiers) && field.declaredClassName == field.typeName &&
                field.declaredClassName.startsWith("androidx.compose.ui.")
        }
        runCatching {
            android.util.Log.d("XBlocker.Media", "emptyModifier=${profileEmptyModifier?.descriptor} " +
                profileListRow?.usingFields?.map { it.field }
                    ?.filter { it.descriptor.startsWith("Landroidx/compose/ui/") }
                    ?.joinToString { "${it.descriptor} static=${Modifier.isStatic(it.modifiers)} decl=${it.declaredClassName} sign=${it.typeSign} name=${it.typeName}" })
        }
        // Overflow menu rows are ProfileMenuItem instances listed by a model class and rendered with
        // the profile action callback; both are discovered by shape.
        val profileMenuItemCtor = profileAction?.declaredClass?.methods
            ?.filter { it.isConstructor && it.paramCount == 4 && it.paramTypeNames.getOrNull(0) == "java.lang.String" }
            ?.singleOrNull()
        val profileMenuModel = profileAction?.let { action ->
            bridges.flatMap { b -> b.findMethod { matcher {
                name = "<init>"
                paramTypes(listOf(action.declaredClassName, action.declaredClassName, action.declaredClassName,
                    action.declaredClassName, action.declaredClassName, "kotlinx.collections.immutable.c"))
            } } }.map { it.declaredClassName }.distinct().singleOrNull()
        }
        val profileMenuRenderer = profileMenuModel?.let { model ->
            methods(listOf(model, F1, F1, MODIFIER, null, null, null, "long", null, null, null, null, null, null,
                COMPOSER, "int", "int")).singleOrNull()
        }
        val profile = if (profileButton != null && profileButtonCtor != null && profileRows.isNotEmpty() &&
            profileImmutable != null && profileOperation != null) {
            ProfileMediaTargets(profileRows, profileButton.declaredClassName,
                profileButtonCtor.descriptor, profileImmutable.descriptor,
                profileOperation.declaredClassName, profileOperation.name,
                profileFollow?.descriptor, profileRenderer?.descriptor, profileEmptyModifier?.descriptor,
                profileAction?.declaredClassName, profileMenuItemCtor?.descriptor,
                profileMenuModel, profileMenuRenderer?.descriptor, downloadIcon)
        } else null
        return MediaMenuTargets(models, videos, photoSheet, popup, render?.descriptor, ctor?.descriptor, id, immutable, iconCtor, row, enums, legacyRows, downloadIcon, trays, rows, profile)
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
            .put("videoTrayCount", t.videoTrays.size).put("videoRowCount", t.videoRows.size)
            .put("modelCount", t.models.modelClasses.size).put("details", if (capabilities.values.all { it }) "matched" else "partial")
            .put("nativeDownloadIcon", t.downloadIconField != null)
    }
}
