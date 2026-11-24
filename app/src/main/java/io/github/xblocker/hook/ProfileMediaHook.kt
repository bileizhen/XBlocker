package io.github.xblocker.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.xblocker.core.ProfileMediaItem
import io.github.xblocker.core.ProfileMediaParser
import io.github.xblocker.core.ProfileMediaRun
import io.github.xblocker.download.MediaDownloadPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.URLEncoder
import java.util.Collections
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val MODULE_PACKAGE = "io.github.bileizhen.xblocker"
private const val MODULE_ACTIVITY = "io.github.xblocker.ui.MainActivity"
private const val MEDIA_CHANNEL = "xblocker_profile_media"
private const val MEDIA_NOTIFICATION_ID = 0x58425331

/**
 * Captures the first authenticated OkHttp client used for X GraphQL calls. Replaying the profile
 * media operation through the same client lets X's OAuth/cookie/language interceptors run.
 */
internal object HostGraphQlClient {
    @Volatile var client: Any? = null
        private set
    private val installed = AtomicBoolean(false)

    fun install(loader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val requestClass = loader.loadClass("okhttp3.Request")
            val clientClass = loader.loadClass("okhttp3.OkHttpClient")
            XposedBridge.hookMethod(clientClass.getMethod("newCall", requestClass), object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (client != null) return
                    runCatching {
                        val request = p.args[0]
                        val url = request.javaClass.getMethod("url").invoke(request) ?: return
                        val host = url.javaClass.getMethod("host").invoke(url) as? String ?: return
                        val path = url.javaClass.getMethod("encodedPath").invoke(url) as? String ?: return
                        if ((host == "api.x.com" || host == "api.twitter.com") && path.startsWith("/graphql/")) {
                            client = p.thisObject
                        }
                    }
                }
            })
        }.onFailure { XposedBridge.log("XBlocker.Profile: network capture skipped: ${it.javaClass.simpleName}") }
    }
}

/**
 * Adds a native "download all" pill to the right of the profile header's Follow button and pages
 * through UserProfileMediaTimelineQuery with the host's authenticated OkHttp client.
 */
internal class ProfileMediaHook(
    private val context: Context,
    private val loader: ClassLoader,
    private val targets: ProfileMediaTargets,
) {
    private data class User(val id: String, val handle: String, val name: String?, val avatar: String?) {
        val folder get() = "$id(@$handle)"
    }

    private data class HttpResult(val code: Int, val body: String, val retryAfter: Long)

    /** Row currently being composed; the follow-button hook appends our pill after it when set. */
    private class RowContext(val user: User, val callback: Any, val marker: Any, val item: Any)

    private val function1 = loader.loadClass("kotlin.jvm.functions.Function1")
    private val invoke1 = function1.getMethod("invoke", Any::class.java)
    private val unit by lazy {
        loader.loadClass("kotlin.Unit").declaredFields.first {
            Modifier.isStatic(it.modifiers) && it.type.name == "kotlin.Unit"
        }.apply { isAccessible = true }.get(null)
    }
    // Two threads so a superseded run (possibly waiting on a rate-limit backoff) never blocks the
// next request; stale runs stop at their next generation check.
    private val worker = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "XBlocker-profile-media").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val bridge = Uri.parse("content://io.github.bileizhen.xblocker.bridge")
    private val rowMethods = targets.rows.map { DexMethod(it).getMethodInstance(loader) }
    private val buttonConstructor = DexMethod(targets.buttonConstructor).getConstructorInstance(loader)
    private val buttonType = buttonConstructor.parameterTypes[0]
    private val buttonMarkerType = buttonConstructor.parameterTypes[2]
    private val buttonTypeValue: Any? = buttonType.enumConstants?.firstOrNull()
    private val immutableList = DexMethod(targets.immutableList).getMethodInstance(loader)
    private val immutableListType = immutableList.returnType
    private val modifierType = loader.loadClass("androidx.compose.ui.Modifier")
    private val composerType = loader.loadClass("androidx.compose.runtime.Composer")
    private val followMethod = targets.follow?.let { DexMethod(it).getMethodInstance(loader) }
    private val renderer = targets.renderer?.let { DexMethod(it).getMethodInstance(loader) }
    private val followComposerIndex = followMethod?.parameterTypes
        ?.indexOfFirst { composerType.isAssignableFrom(it) } ?: -1
    private val emptyModifier: Any? by lazy {
        targets.emptyModifier?.let { descriptor ->
            runCatching { DexField(descriptor).getFieldInstance(loader).get(null) }
                .getOrNull()?.takeIf { modifierType.isInstance(it) }
        }
    }
    private val activeRow = ThreadLocal<RowContext?>()
    private val items = Collections.synchronizedMap(HashMap<String, Any>())
    private val menuItemClass = targets.menuItem?.let { loader.loadClass(it) }
    private val menuItemConstructor = targets.menuItemConstructor?.let { DexMethod(it).getConstructorInstance(loader) }
    private val menuModelClass = targets.menuModel?.let { loader.loadClass(it) }
    private val menuRenderer = targets.menuRenderer?.let { DexMethod(it).getMethodInstance(loader) }
    private val menuMarkerField = menuItemClass?.let { type -> fieldsOf(type).firstOrNull { it.type == buttonMarkerType } }
    private val menuIconField = menuItemClass?.let { type ->
        menuItemConstructor?.parameterTypes?.getOrNull(1)?.let { iconType -> fieldsOf(type).firstOrNull { it.type == iconType } }
    }
    private val menuIconValue: Any? by lazy {
        targets.menuIcon?.let { runCatching { DexField(it).getFieldInstance(loader).get(null) }.getOrNull() }
    }
    @Volatile private var profileUser: User? = null
    private val photosMarker by lazy { createMarker("XBlocker download photos") }
    private val videosMarker by lazy { createMarker("XBlocker download videos") }
    private val menuActions = Collections.synchronizedMap(IdentityHashMap<Any, (User) -> Unit>())
    private val menuWrappers = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val operationClass = loader.loadClass(targets.operation)
    private val operationNameMethod = operationClass.getMethod(targets.operationName)
    private val operationConstructor = operationClass.getConstructor(String::class.java, String::class.java)
    private val markers = Collections.synchronizedMap(HashMap<String, Any>())
    private val markerUsers = Collections.synchronizedMap(IdentityHashMap<Any, User>())
    private val callbackWrappers = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val http by lazy { Http() }
    @Volatile private var queryId: String? = null

    fun install() {
        check(buttonConstructor.parameterCount == 3) { "profile button shape changed" }
        check(buttonTypeValue != null) { "profile button type is not an enum" }
        HostGraphQlClient.install(loader)
        // The phone row renders the pill list first and the follow button last, so the download
        // pill is emitted right after the follow button returns (still inside the row's Row).
        followMethod?.takeIf { followComposerIndex >= 0 }?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching { emitAfterFollow(p) }.onFailure {
                        XposedBridge.log("XBlocker.Profile: trailing button skipped: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                    }
                }
            })
        }
        rowMethods.forEach { rowMethod ->
            XposedBridge.hookMethod(rowMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching { decorate(p) }.onFailure {
                        XposedBridge.log("XBlocker.Profile: button skipped: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                    }
                }
                override fun afterHookedMethod(p: MethodHookParam) { activeRow.remove() }
            })
        }
        // Overflow menu: append photo/video only entries to the menu model and wrap the action
        // callback so their markers start a filtered collection run instead of an X action.
        menuModelClass?.declaredConstructors?.forEach { ctor ->
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching { decorateMenu(p) }.onFailure {
                        XposedBridge.log("XBlocker.Profile: menu item skipped: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                    }
                }
            })
        }
        menuRenderer?.let { method ->
            val indices = method.parameterTypes.indices.filter { function1.isAssignableFrom(method.parameterTypes[it]) }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        for (index in indices) {
                            val original = p.args.getOrNull(index) ?: continue
                            p.args[index] = wrapMenuCallback(original)
                        }
                    }.onFailure {
                        XposedBridge.log("XBlocker.Profile: menu callback skipped: ${it.javaClass.simpleName}")
                    }
                }
            })
        }
    }

    /**
     * Phone rows pass (pills, onAction, Modifier, state, Composer, flags); tablets pass
     * (state, onAction, followState, pills, Modifier, Composer, flags). Locate the immutable
     * pill list, the action callback and the header state by type instead of argument index.
     */
    private fun decorate(p: XC_MethodHook.MethodHookParam) {
        val listIndex = p.args.indexOfFirst { it != null && immutableListType.isInstance(it) }
        if (listIndex < 0) return
        val callbackIndex = p.args.indexOfFirst { it != null && function1.isInstance(it) }
        if (callbackIndex < 0) return
        val callback = p.args[callbackIndex]
        var user: User? = null
        var stateIndex = -1
        for ((index, value) in p.args.withIndex()) {
            if (value == null || index == listIndex || index == callbackIndex) continue
            if (value is Number || value is Boolean || value is Char) continue
            if (modifierType.isInstance(value)) continue
            if (value.javaClass.name.startsWith("androidx.compose.runtime.")) continue
            user = findUser(value)
            if (user != null) { stateIndex = index; break }
        }
        if (user == null) return
        runCatching { MediaDownloadPolicy.folder(user.folder) }.getOrElse { return }
        profileUser = user
        ProfileFocus.remember(user.id, user.handle, user.name, user.avatar)
        val marker = markers[user.id] ?: createMarker().also {
            // Trim before inserting, otherwise the 65th profile clears its own marker.
            if (markers.size >= 64) { markers.clear(); markerUsers.clear(); items.clear() }
            markers[user.id] = it
            markerUsers[it] = user
        }
        val type = buttonTypeValue ?: return
        val item = items[user.id] ?: buttonConstructor.newInstance(type, label(), marker).also {
            items[user.id] = it
            if (items.size > 64) items.clear()
        }
        p.args[callbackIndex] = wrapCallback(callback, marker)
        // List-before-follow rows (phones) get the pill appended after the follow button instead,
        // so it stays the rightmost action. Rows that render the list last append it directly.
        if (followMethod != null && renderer != null && emptyModifier != null && stateIndex > listIndex) {
            activeRow.set(RowContext(user, p.args[callbackIndex], marker, item))
            return
        }
        val originalList = (p.args[listIndex] as? List<*>) ?: emptyList<Any?>()
        if (originalList.any { handlerOf(it) === marker }) return
        val target = ArrayList<Any?>(originalList.size + 1)
        target.addAll(originalList)
        target.add(item)
        val list = immutableList.invoke(null, target) ?: return
        p.args[listIndex] = list
    }

    private fun emitAfterFollow(p: XC_MethodHook.MethodHookParam) {
        if (p.hasThrowable()) return
        val ctx = activeRow.get() ?: return
        val composer = p.args.getOrNull(followComposerIndex)
        val modifier = emptyModifier
        val target = renderer ?: return
        if (composer == null || modifier == null) return
        // Called between the follow composable's return and the row's endReplaceableGroup: the
        // emitted node is a sibling of the follow button inside the same Row, after it.
        target.invoke(null, ctx.item, ctx.callback, modifier, composer, 0)
    }

    private fun handlerOf(item: Any?): Any? {
        if (item == null) return null
        return fields(item).firstOrNull { it.type == buttonMarkerType }
            ?.let { runCatching { it.get(item) }.getOrNull() }
    }

    private fun createMarker(text: String = "XBlocker download all media"): Any = Proxy.newProxyInstance(loader, arrayOf(buttonMarkerType), object : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> text
            else -> null
        }
    })

    private fun wrapCallback(original: Any, marker: Any): Any = callbackWrappers[original] ?: Proxy.newProxyInstance(
        loader, arrayOf(function1), object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "XBlocker profile action"
                "invoke" -> {
                    val value = args?.firstOrNull()
                    val user = markerUsers[value]
                    if (user != null && markers[user.id] === value) {
                        start(user)
                        unit
                    } else invoke1.invoke(original, value)
                }
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        }
    ).also { callbackWrappers[original] = it }

    /** Appends the two filtered-download entries to the profile overflow menu's item list. */
    private fun decorateMenu(p: XC_MethodHook.MethodHookParam) {
        if (profileUser == null) return
        val ctor = menuItemConstructor ?: return
        val markerField = menuMarkerField ?: return
        val itemClass = menuItemClass ?: return
        val listIndex = p.args.indexOfFirst { it != null && immutableListType.isInstance(it) }
        if (listIndex < 0) return
        val original = p.args[listIndex] as? List<*> ?: return
        val markers = listOf(photosMarker, videosMarker)
        if (original.any { item -> markers.any { marker -> markerOf(item, markerField) === marker } }) return
        val icon = menuIconValue ?: p.args.filterNotNull().filter { itemClass.isInstance(it) }
            .mapNotNull { item -> menuIconField?.let { field -> runCatching { field.get(item) }.getOrNull() } }
            .firstOrNull()
        val photos = ctor.newInstance(labelPhotos(), icon, photosMarker, null)
        val videos = ctor.newInstance(labelVideos(), icon, videosMarker, null)
        menuActions[photosMarker] = { user -> start(user, MediaFilter.PHOTOS) }
        menuActions[videosMarker] = { user -> start(user, MediaFilter.VIDEOS) }
        val target = ArrayList<Any?>(original.size + 2)
        target.addAll(original)
        target.add(photos)
        target.add(videos)
        val list = immutableList.invoke(null, target) ?: return
        p.args[listIndex] = list
    }

    private fun markerOf(item: Any?, field: Field): Any? = item?.let { runCatching { field.get(it) }.getOrNull() }

    private fun wrapMenuCallback(original: Any): Any = menuWrappers[original] ?: Proxy.newProxyInstance(
        loader, arrayOf(function1), object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "XBlocker profile menu action"
                "invoke" -> {
                    val value = args?.firstOrNull()
                    val action = if (value != null) menuActions[value] else null
                    val user = profileUser
                    if (action != null && user != null) {
                        action(user)
                        unit
                    } else invoke1.invoke(original, value)
                }
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        }
    ).also { menuWrappers[original] = it }

    private fun labelPhotos() = if (chinese()) "仅下载所有照片" else "Download all photos"
    private fun labelVideos() = if (chinese()) "仅下载所有视频" else "Download all videos"

    private fun fields(value: Any): List<Field> = fieldsOf(value.javaClass)

    private fun fieldsOf(start: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var type: Class<*>? = start
        repeat(5) {
            val owner = type ?: return@repeat
            owner.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                runCatching { it.isAccessible = true }
                result += it
            }
            type = owner.superclass
        }
        return result
    }

    /** The header state's model prints as ProfileUser(id=..., screenName=..., name=..., profileImageUrl=...). */
    private fun findUser(state: Any): User? {
        for (value in fields(state).mapNotNull { runCatching { it.get(state) }.getOrNull() }.take(64)) {
            val text = runCatching { value.toString() }.getOrNull() ?: continue
            if (!text.startsWith("ProfileUser(id=")) continue
            parseUser(text, value)?.let { return it }
        }
        return null
    }

    private fun parseUser(text: String, value: Any): User? {
        val id = text.substringAfter("ProfileUser(id=", "").substringBefore(", screenName=", "")
        if (!id.matches(Regex("\\d{1,32}"))) return null
        val handle = text.substringAfter(", screenName=", "").substringBefore(", name=", "").removePrefix("@")
        if (!handle.matches(Regex("[A-Za-z0-9_]{1,15}"))) return null
        val namePosition = text.indexOf(", name=").takeIf { it >= 0 }?.plus(", name=".length)
        val strings = fields(value).filter { it.type == String::class.java }
            .mapNotNull { runCatching { it.get(value) as? String }.getOrNull() }
        val name = namePosition?.let { position ->
            strings.firstOrNull { candidate ->
                text.startsWith(candidate, position) && text.startsWith(", verifiedType=", position + candidate.length)
            } ?: text.substring(position).substringBefore(", verifiedType=", "").takeIf { it != "null" }
        }?.takeIf { it.isNotBlank() && it.length <= 64 && it.none { c -> c.isISOControl() } }
        val avatar = text.substringAfter(", profileImageUrl=", "").substringBefore(", isProtected=", "")
            .takeIf { it.startsWith("https://pbs.twimg.com/profile_images/") && it.length <= 512 }
        return User(id, handle, name, avatar)
    }

    private fun label() = if (chinese()) "下载全部" else "Download all"

    private enum class MediaFilter(val zh: String, val en: String) {
        ALL("", ""), PHOTOS("照片", "photos"), VIDEOS("视频", "videos");
        fun accepts(type: String) = when (this) {
            ALL -> true
            PHOTOS -> type == "image"
            VIDEOS -> type == "video"
        }
    }

    private fun start(user: User, filter: MediaFilter = MediaFilter.ALL) {
        // A new request supersedes any run still waiting on the previous profile or a backoff;
        // the notification immediately switches to the profile the user just tapped.
        val runId = generation.incrementAndGet()
        val kind = if (chinese()) filter.zh else filter.en
        toast(if (chinese()) "正在获取 @${user.handle} 的${kind}…" else "Collecting ${if (kind.isEmpty()) "media" else kind} from @${user.handle}…")
        notifyProgress(user, 0, 0)
        worker.execute {
            var queued = 0
            var skipped = 0
            var pages = 0
            var dryPages = 0
            try {
                val run = ProfileMediaRun(maxPages = 200, maxMedia = 3_000)
                var cursor: String? = null
                while (!run.done && generation.get() == runId) {
                    val pageJson = fetchPage(user.id, cursor, runId) { seconds ->
                        // A superseded run must not resurrect the notification the new run owns.
                        if (generation.get() == runId) notifyProgress(user, pages, run.accepted,
                            if (chinese()) "被限流，${seconds} 秒后重试…" else "Rate limited, retrying in ${seconds}s…")
                    }
                    val page = ProfileMediaParser.parse(pageJson, cursor)
                    if (!page.recognized) throw IllegalStateException("unrecognized")
                    if (generation.get() != runId) throw Superseded()
                    val fresh = run.accept(page)
                    skipped += page.skipped + fresh.count { !it.allowDownload }
                    val wanted = fresh.filter { it.allowDownload && filter.accepts(it.type) }
                    val result = enqueue(user, wanted)
                    queued += result.first
                    skipped += result.second
                    pages++
                    notifyProgress(user, pages, run.accepted)
                    if (run.done) break
                    // A media timeline ends with repeated empty pages; stop early instead of
                    // walking the host's whole cursor chain for a handful of media.
                    dryPages = if (fresh.isEmpty()) dryPages + 1 else 0
                    if (dryPages >= 3) break
                    val next = page.nextCursor ?: break
                    if (next == cursor) break
                    cursor = next
                    pause(1_200, runId)
                }
                if (generation.get() != runId) throw Superseded()
                val message = when {
                    queued == 0 && skipped > 0 ->
                        if (chinese()) "这 $skipped 个媒体已经下载过了" else "All $skipped media were already downloaded"
                    queued == 0 -> if (chinese()) "没有找到可下载的媒体" else "No downloadable media found"
                    else -> if (chinese()) "已加入 $queued 个下载到 Download/X/${user.folder}/" +
                        (if (skipped > 0) "，跳过 $skipped 个" else "")
                    else "Queued $queued downloads to Download/X/${user.folder}/" +
                        (if (skipped > 0) ", skipped $skipped" else "")
                }
                toast(message)
                XposedBridge.log("XBlocker.Profile: pages=$pages queued=$queued skipped=$skipped capped=${run.capped}")
            } catch (e: Throwable) {
                if (e is Superseded || generation.get() != runId) return@execute
                val message = when {
                    HostGraphQlClient.client == null -> if (chinese()) "X 网络尚未就绪，请先打开一次时间线后重试"
                    else "X network is not ready; open a timeline once and retry"
                    e is HttpException -> if (chinese()) "获取媒体失败（HTTP ${e.code}）" else "Media request failed (HTTP ${e.code})"
                    e.message == "unrecognized" -> if (chinese()) "无法识别媒体响应，可能需要适配此 X 版本"
                    else "Could not parse the media response"
                    else -> if (chinese()) "获取媒体失败：${e.javaClass.simpleName}" else "Media collection failed: ${e.javaClass.simpleName}"
                }
                toast(message)
                XposedBridge.log("XBlocker.Profile: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            } finally {
                if (generation.get() == runId) cancelProgress()
            }
        }
    }

    private class Superseded : Exception("superseded")

    /** Sleeps in slices so a superseded run stops quickly instead of holding its thread. */
    private fun pause(millis: Long, runId: Int) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (generation.get() != runId) throw Superseded()
            Thread.sleep(100)
        }
    }

    /** Ongoing notification so long timelines show page/media counts instead of a silent wait. */
    private fun notifyProgress(user: User, pages: Int, found: Int, note: String? = null) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(MEDIA_CHANNEL,
                if (chinese()) "媒体抓取" else "Media fetch", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
            val text = note ?: when {
                pages <= 0 -> if (chinese()) "正在读取第 1 页…" else "Reading page 1…"
                else -> if (chinese()) "已获取 $pages 页 · 发现 $found 个媒体"
                else "Fetched $pages pages · $found media"
            }
            val launch = Intent().setComponent(ComponentName(MODULE_PACKAGE, MODULE_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = Notification.Builder(context, MEDIA_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (chinese()) "正在获取 @${user.handle} 的媒体"
                else "Fetching media from @${user.handle}")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(pending)
            manager.notify(MEDIA_NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelProgress() {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(MEDIA_NOTIFICATION_ID) }
    }

    private fun fetchPage(restId: String, cursor: String?, runId: Int, onRateLimit: (Long) -> Unit): String {
        var attempt = 0
        while (true) {
            if (generation.get() != runId) throw Superseded()
            val result = execute(restId, cursor)
            if (result.code in 200..299) return result.body
            if ((result.code == 429 || result.code >= 500) && attempt++ < 3) {
                val wait = result.retryAfter.coerceIn(1, 60)
                onRateLimit(wait)
                pause(wait * 1000, runId)
                continue
            }
            throw HttpException(result.code)
        }
    }

    private fun execute(restId: String, cursor: String?): HttpResult {
        val client = HostGraphQlClient.client ?: throw IllegalStateException("network-not-ready")
        val operation = operationConstructor.newInstance(restId, cursor)
        val name = operationNameMethod.invoke(operation) as String
        val id = queryId ?: resolveQueryId(operation, name)?.also { queryId = it }
            ?: throw IllegalStateException("query-id-missing")
        val variables = JSONObject().put("rest_id", restId).put("count", JSONObject.NULL)
            .put("cursor", cursor ?: JSONObject.NULL).put("autoplay_enabled", JSONObject.NULL)
            .put("context", JSONObject.NULL).put("cards_platform", JSONObject.NULL).toString()
        val url = "https://api.x.com/graphql/$id/$name?variables=" + URLEncoder.encode(variables, "UTF-8")
        return http.execute(client, url)
    }

    private fun resolveQueryId(operation: Any, name: String): String? = operationClass.declaredMethods
        .filter { it.parameterCount == 0 && it.returnType == String::class.java }
        .mapNotNull { method -> runCatching { method.invoke(operation) as? String }.getOrNull() }
        .firstOrNull { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) && it != name }

    private fun enqueue(user: User, items: List<ProfileMediaItem>): Pair<Int, Int> {
        if (items.isEmpty()) return 0 to 0
        var queued = 0
        var rejected = 0
        for (chunk in items.chunked(150)) {
            val array = JSONArray()
            chunk.forEach { array.put(JSONObject().put("url", it.url).put("tweetId", it.tweetId)
                .put("mediaId", it.mediaId).put("type", it.type)) }
            val result = runCatching {
                context.contentResolver.call(bridge, "downloadMediaBatch", null, Bundle().apply {
                    putString("folder", user.folder)
                    putString("userId", user.id)
                    putString("userHandle", user.handle)
                    putString("userName", user.name)
                    putString("userAvatar", user.avatar)
                    putString("items", array.toString())
                })
            }.getOrNull()
            if (result == null) rejected += chunk.size
            else {
                queued += result.getInt("queued", 0)
                rejected += result.getInt("rejected", 0)
                rejected += result.getInt("skipped", 0)
            }
        }
        return queued to rejected
    }

    private fun toast(text: String) { main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() } }

    private fun chinese() = context.resources.configuration.locales[0].language == "zh"

    private class HttpException(val code: Int) : Exception("HTTP $code")

    /** Reflection over the host's own OkHttp, whose unobfuscated classes are shared by X. */
    private inner class Http {
        private val requestClass = loader.loadClass("okhttp3.Request")
        private val builderClass = loader.loadClass("okhttp3.Request\$Builder")
        private val callClass = loader.loadClass("okhttp3.Call")
        private val responseClass = loader.loadClass("okhttp3.Response")
        private val bodyClass = loader.loadClass("okhttp3.ResponseBody")
        private val builderCtor = builderClass.getConstructor()
        private val setUrl = builderClass.getMethod("url", String::class.java)
        private val setHeader = builderClass.getMethod("header", String::class.java, String::class.java)
        private val getMethod = builderClass.getMethod("get")
        private val build = builderClass.getMethod("build")
        private val newCall = loader.loadClass("okhttp3.OkHttpClient").getMethod("newCall", requestClass)
        private val callTimeoutGetter = callClass.getMethod("timeout")
        // okio is obfuscated in the host; take the Timeout class from the accessor's return type.
        private val callTimeoutSetter = callTimeoutGetter.returnType
            .getMethod("timeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
        private val executeCall = callClass.getMethod("execute")
        private val responseCode = responseClass.getMethod("code")
        private val responseBody = responseClass.getMethod("body")
        private val responseHeader = responseClass.getMethod("header", String::class.java)
        private val closeResponse = responseClass.getMethod("close")
        private val bodyString = bodyClass.getMethod("string")

        fun execute(client: Any, url: String): HttpResult {
            val builder = builderCtor.newInstance()
            setUrl.invoke(builder, url)
            setHeader.invoke(builder, "Accept", "application/json")
            setHeader.invoke(builder, "Apollo-Require-Preflight", "true")
            getMethod.invoke(builder)
            val request = build.invoke(builder)
            val call = newCall.invoke(client, request)
            // X's client may have no timeouts; a hung call would otherwise pin this run forever.
            runCatching {
                callTimeoutSetter.invoke(callTimeoutGetter.invoke(call), 25_000L, TimeUnit.SECONDS)
            }
            val response = executeCall.invoke(call) ?: throw IllegalStateException("no response")
            return try {
                val code = responseCode.invoke(response) as Int
                val body = responseBody.invoke(response)
                val text = if (body == null) "" else bodyString.invoke(body) as? String ?: ""
                val retryAfter = (responseHeader.invoke(response, "retry-after") as? String)?.toLongOrNull()
                    ?: (responseHeader.invoke(response, "x-rate-limit-reset") as? String)?.toLongOrNull()
                        ?.minus(System.currentTimeMillis() / 1000) ?: 15L
                HttpResult(code, text, retryAfter)
            } finally {
                runCatching { closeResponse.invoke(response) }
            }
        }
    }
}