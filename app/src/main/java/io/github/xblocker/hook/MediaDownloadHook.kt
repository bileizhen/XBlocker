package io.github.xblocker.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Native adapters discovered from behavior and signatures, without a version allowlist. */
internal class MediaDownloadHook(private val context: Context, private val loader: ClassLoader) {
    private val function1 = loader.loadClass("kotlin.jvm.functions.Function1")
    private val function0 = loader.loadClass("kotlin.jvm.functions.Function0")
    private val invoke1 = function1.getMethod("invoke", Any::class.java)
    private val invoke0 = function0.getMethod("invoke")
    private val unit by lazy { loader.loadClass("kotlin.Unit").declaredFields.first {
        Modifier.isStatic(it.modifiers) && it.type.name == "kotlin.Unit"
    }.apply { isAccessible = true }.get(null) }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "XBlocker-download").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile var status: String = "scanning"
        private set
    @Volatile var scanReport: JSONObject? = null
        private set
    private var loggedError = false
    private lateinit var targets: MediaMenuTargets
    private class Binding(val model: () -> Any?, val dismiss: (() -> Unit)?, val video: Boolean,
                          val host: () -> List<Any?>)
    @Volatile private var trayDownload: Any? = null
    private val trayBindings = Collections.synchronizedMap(WeakHashMap<Any, Binding>())
    private val trayProxies = Collections.synchronizedMap(WeakHashMap<Any, MutableMap<Any, Any>>())
    private inner class ActionHandler(val original: Any, val binding: Binding, val intercept: (Any?) -> Boolean,
                                      val fallbackOnFailure: Boolean) : InvocationHandler {
        private fun delegate(method: Method, args: Array<out Any?>?): Any? =
            if (method.parameterCount == 0) invoke0.invoke(original) else invoke1.invoke(original, args?.firstOrNull())
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "XBlocker media action"
            "invoke" -> {
                val value = args?.firstOrNull()
                if (intercept(value)) {
                    if (download(binding)) { runCatching { binding.dismiss?.invoke() }; unit }
                    else if (fallbackOnFailure) delegate(method, args)
                    else { toastNoMedia(); unit }
                } else delegate(method, args)
            }
            else -> method.invoke(original, *(args ?: emptyArray()))
        }
    }

    fun install() {
        val resolution = MediaMenuLocator.resolve(context, loader)
        scanReport = resolution.report
        targets = resolution.targets ?: run {
            status = "scan_failed"
            XposedBridge.log("XBlocker.Media: scan_failed ${resolution.report}")
            return
        }
        var installed = 0
        fun model(value: Any) = HostMediaSelection.findInCallback(value, targets.models)
        fun installOne(descriptor: String?, action: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
            descriptor ?: return false
            return runCatching { hook(DexMethod(descriptor).getMethodInstance(loader), action); installed++; true }
                .getOrElse { logOnce(it); false }
        }
        fun installOneAfter(descriptor: String?, action: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
            descriptor ?: return false
            return runCatching { hookAfter(DexMethod(descriptor).getMethodInstance(loader), action); installed++; true }
                .getOrElse { logOnce(it); false }
        }
        targets.videoMenus.forEach { descriptor -> installOne(descriptor) { p ->
            val state = p.args[0]
            val dismiss = p.args[1]
            p.args[2] = wrap(unwrap(p.args[2]), Binding({ model(state) }, { invoke0.invoke(dismiss) }, true, { listOf(state, dismiss) }))
        } }
        // Immersive player (X 12.45+): rows come from a state-derived enum list, and each plain
        // action row dispatches its enum through one of the sheet's Function1 parameters.
        var trayHooked = false
        targets.videoTrays.forEach { descriptor -> trayHooked = installOne(descriptor) { p ->
            val state = p.args[0]
            val binding = trayBindings.getOrPut(state) { Binding({ model(state) }, null, true, { listOf(state) }) }
            val proxies = trayProxies.getOrPut(state) { IdentityHashMap() }
            if (trayProxies.size > 32) { trayProxies.clear(); trayBindings.clear() }
            (p.method as? Method)?.parameterTypes.orEmpty().forEachIndexed { index, type ->
                if (index == 0 || type != function1) return@forEachIndexed
                val original = unwrap(p.args[index] ?: return@forEachIndexed)
                p.args[index] = proxies.getOrPut(original) {
                    wrap(original, binding, fallbackOnFailure = true) { value -> value != null && value === trayDownload }
                }
            }
        } || trayHooked }
        if (trayHooked) targets.videoRows.forEach { descriptor -> installOneAfter(descriptor) { p ->
            @Suppress("UNCHECKED_CAST")
            val items = p.result as? MutableList<Any> ?: return@installOneAfter
            val sample = items.firstOrNull { it is Enum<*> } ?: return@installOneAfter
            val enumClass = if (sample.javaClass.isEnum) sample.javaClass else sample.javaClass.superclass
            val download = enumClass?.enumConstants
                ?.firstOrNull { (it as? Enum<*>)?.name == "DownloadVideo" } ?: return@installOneAfter
            trayDownload = download
            if (items.any { it === download }) return@installOneAfter
            // Require an MP4, but keep the row even when the author disabled downloads.
            val selected = model(p.args[0])
            val media = runCatching { HostMediaSelection.fromModel(selected, targets.models) }.getOrNull()
            val downloadable = HostMediaSelection.isDownloadable(selected, targets.models)
            if (io.github.xblocker.BuildConfig.DEBUG) XposedBridge.log(
                "XBlocker.Media: rows size=${items.size} added=${media?.type == "video"} " +
                    "type=${media?.type} nativeDownloadable=$downloadable")
            if (media?.type != "video") return@installOneAfter
            items.add(download)
        } }
        installOne(targets.photoSheet) { p ->
            val original = unwrap(p.args[1])
            if (model(original) == null) return@installOne
            val dismiss = p.args[2]
            p.args[1] = wrap(original, Binding({ model(original) }, { invoke0.invoke(dismiss) }, false, { listOf(original, p.args.getOrNull(0), dismiss) }))
        }
        installOne(targets.photoPopup) { p ->
            val original = unwrap(p.args[2])
            if (model(original) == null) return@installOne
            val dismiss = p.args[1]
            val download = targets.photoDownloadEnum?.let { loader.loadClass(it).enumConstants?.singleOrNull { value -> (value as? Enum<*>)?.name == "Download" } }
                ?: return@installOne
            val callback = wrap(original, Binding({ model(original) }, { invoke0.invoke(dismiss) }, false,
                { listOf(original, p.args.getOrNull(3), dismiss) })) { it === download }
            val options = p.args[3] as List<*>
            p.args[2] = callback
            if (download !in options) p.args[3] = options + download
        }
        if (targets.sheetReady) {
            val constructor = DexMethod(requireNotNull(targets.itemConstructor)).getConstructorInstance(loader)
            val idField = DexField(requireNotNull(targets.itemIdField)).getFieldInstance(loader).apply { isAccessible = true }
            val toImmutable = DexMethod(requireNotNull(targets.toImmutable)).getMethodInstance(loader)
            val icon = targets.downloadIconField?.let { descriptor -> runCatching {
                DexField(descriptor).getFieldInstance(loader).apply { isAccessible = true }.get(null)
            }.getOrNull() } ?: targets.iconConstructor?.let { descriptor -> runCatching {
                val resource = context.resources.getIdentifier("ic_vector_incoming_fill", "drawable", context.packageName)
                if (resource != 0) DexMethod(descriptor).getConstructorInstance(loader).newInstance(resource, false) else null
            }.getOrNull() }
            installOne(targets.sheetRender) { p ->
                val original = p.args[1]
                val binding = bindingOf(original, 0) ?: return@installOne
                val items = (p.args[0] as List<*>).filterNot {
                    binding.video && runCatching { (idField.get(it) as? Enum<*>)?.name == "DownloadVideo" }.getOrDefault(false)
                }
                if (items.any { runCatching { idField.get(it) == ACTION }.getOrDefault(false) }) return@installOne
                val item = constructor.newInstance(ACTION, icon, label(binding.video), "Download/X/", null, 0x70)
                val updated = toImmutable.invoke(null, listOf(item) + items)
                val callback = wrap(original, binding) { value -> idField.declaringClass.isInstance(value) && idField.get(value) == ACTION }
                p.args[0] = updated
                p.args[1] = callback
            }
        }
        targets.profile?.let { profile ->
            runCatching { ProfileMediaHook(context, loader, profile).install(); installed++ }
                .onFailure {
                    logOnce(it)
                    XposedBridge.log("XBlocker.Profile: install skipped: ${it.javaClass.simpleName}")
                }
        }
        installOne(targets.popupRow) { p ->
            if (bindingOf(p.args[1], 0) != null && hasDownloadAction(p.args[1])) p.args[0] = label(false)
        }
        // In older builds the photo menu is inlined. Preserve its localized Save label and
        // intercept only the joint Download action and selected-image evidence.
        targets.legacyRows.forEach { descriptor -> installOne(descriptor) { p ->
            val callback = p.args[1]
            if (isOwnProxy(callback) || !hasDownloadAction(callback)) return@installOne
            val selected = model(callback) ?: return@installOne
            if (HostMediaSelection.fromModel(selected, targets.models)?.type != "image") return@installOne
            val binding = Binding({ model(callback) }, { dismissCapturedPopup(callback) }, false, { listOf(callback) })
            p.args[1] = wrap(callback, binding, zeroArgs = true) { true }
        } }
        status = if (installed > 0) "native_menus:$installed/${resolution.report.optString("mode")}" else "no_compatible_menu"
        XposedBridge.log("XBlocker.Media: $status")
    }

    private fun unwrap(value: Any): Any = if (isOwnProxy(value)) (Proxy.getInvocationHandler(value) as ActionHandler).original else value
    private fun isOwnProxy(value: Any) = Proxy.isProxyClass(value.javaClass) && Proxy.getInvocationHandler(value) is MediaDownloadHook.ActionHandler
    private fun wrap(original: Any, binding: Binding, zeroArgs: Boolean = false,
                     fallbackOnFailure: Boolean = false, intercept: (Any?) -> Boolean = { false }): Any =
        Proxy.newProxyInstance(loader, arrayOf(if (zeroArgs) function0 else function1),
            ActionHandler(original, binding, intercept, fallbackOnFailure))
    private fun bindingOf(callback: Any, depth: Int): Binding? {
        if (isOwnProxy(callback)) return (Proxy.getInvocationHandler(callback) as ActionHandler).binding
        if (depth >= 3) return null
        return captures(callback).filter { function1.isInstance(it) || function0.isInstance(it) }
            .firstNotNullOfOrNull { bindingOf(it, depth + 1) }
    }
    private fun captures(value: Any) = value.javaClass.declaredFields.asSequence().filter { !Modifier.isStatic(it.modifiers) }
        .mapNotNull { runCatching { it.isAccessible = true; it.get(value) }.getOrNull() }
    private fun hasDownloadAction(value: Any) = captures(value).any { (it as? Enum<*>)?.name == "Download" }
    private fun dismissCapturedPopup(callback: Any) {
        val states = captures(callback).mapNotNull { value -> runCatching {
            val get = value.javaClass.getMethod("getValue").apply { isAccessible = true }
            val set = value.javaClass.getMethod("setValue", Any::class.java).apply { isAccessible = true }
            if (get.invoke(value) == true) Pair(value, set) else null
        }.getOrNull() }.toList()
        states.singleOrNull()?.let { (state, set) -> set.invoke(state, false) }
    }
    private fun download(binding: Binding): Boolean {
        val media = runCatching { HostMediaSelection.fromModel(binding.model(), targets.models) }.getOrNull()
        if (media == null) return false
        // Save under the tweet author's folder when the viewer state exposes it, like "download all".
        val fromViewer = runCatching {
            binding.host().firstNotNullOfOrNull { root -> HostMediaSelection.findUser(root) }
        }.getOrNull()
        val user = fromViewer ?: if (ProfileFocus.fresh()) {
            ProfileFocus.id?.let { id ->
                ProfileFocus.handle?.let { handle ->
                    HostMediaSelection.User(id, handle, ProfileFocus.name, ProfileFocus.avatar)
                }
            }
        } else null
        if (io.github.xblocker.BuildConfig.DEBUG) XposedBridge.log("XBlocker.Media: viewer author=@${
            user?.handle ?: "null"} source=${if (fromViewer != null) "viewer" else if (user != null) "profile" else "none"}")
        val folder = user?.let { "${it.id}(@${it.handle})" }
        worker.execute {
            val result = runCatching { context.contentResolver.call(Uri.parse("content://io.github.bileizhen.xblocker.bridge"), "downloadMedia", null,
                Bundle().apply {
                    putString("url", media.url); putString("mediaId", media.mediaId)
                    putString("tweetId", media.tweetId); putString("type", media.type)
                    if (user != null && folder != null) {
                        putString("folder", folder)
                        putString("userId", user.id)
                        putString("userHandle", user.handle)
                        putString("userName", user.name)
                        putString("userAvatar", user.avatar)
                    }
                }) }.getOrNull()
            val destination = folder?.let { "Download/X/$it/" } ?: "Download/X/"
            toast(if (result?.getBoolean("queued") == true) {
                if (chinese()) "已加入下载，保存到 $destination" else "Download queued for $destination"
            } else if (chinese()) "无法开始下载，请打开 XBlocker 后重试" else "Could not start download; open XBlocker and retry")
        }
        return true
    }
    private fun toastNoMedia() {
        toast(if (chinese()) "当前媒体没有可下载的图片或 MP4" else "No downloadable image or MP4 for this media")
    }
    private fun label(video: Boolean) = if (chinese()) { if (video) "下载视频" else "下载图片" } else { if (video) "Download video" else "Download image" }
    private fun toast(text: String) { main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() } }
    private fun chinese() = context.resources.configuration.locales[0].language == "zh"
    private fun logOnce(error: Throwable) { if (!loggedError) { loggedError = true; XposedBridge.log("XBlocker.Media: menu skipped: ${error.javaClass.simpleName}") } }
    private fun hook(method: Method, action: (XC_MethodHook.MethodHookParam) -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) { runCatching { action(p) }.onFailure(::logOnce) }
        })
    }
    private fun hookAfter(method: Method, action: (XC_MethodHook.MethodHookParam) -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { runCatching { action(p) }.onFailure(::logOnce) }
        })
    }
    private companion object { const val ACTION = "io.github.bileizhen.xblocker.DOWNLOAD_MEDIA" }
}
