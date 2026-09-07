package io.github.xblocker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import io.github.xblocker.ui.component.FloatingBottomBar
import io.github.xblocker.ui.component.FloatingBottomBarItem
import io.github.xblocker.ui.util.BlurredBar
import io.github.xblocker.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.xblocker.core.RuleParser
import io.github.xblocker.core.Tweet
import io.github.xblocker.data.UpdateDownloadState
import io.github.xblocker.data.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = runCatching { io.github.xblocker.data.Repository(this) }
        // Read the color mode synchronously once so the first frame already matches.
        val initialColorMode = runCatching { repo.getOrNull()?.colorMode() ?: 0 }.getOrDefault(0)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDarkMode(initialColorMode, systemDark)
        setContent { XBlockerApp(initialColorMode) }
    }
}

/** Mirrors SuKIsu's ColorMode helpers: 0/3 follow system, 1/4 light, 2/5 dark. */
private fun isDarkMode(colorMode: Int, systemDark: Boolean): Boolean = when (colorMode) {
    1, 4 -> false
    2, 5 -> true
    else -> systemDark
}

// Reference screenshots use the Miuix black surface and #242424 card palette.
private fun amoledDark() = darkColorScheme(background = Color.Black)

/** SukiSU ThemeController mapping with dark-mode and Monet modes. */
@Composable
private fun XBlockerApp(initialColorMode: Int, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colorMode = if (state.ready) state.colorMode else initialColorMode
    val systemDark = isSystemInDarkTheme()
    val darkTheme = isDarkMode(colorMode, systemDark)
    // ThemeController fields are read-only, so rebuild it whenever the mode changes —
    // exactly how SukiSU constructs it inside composition.
    val controller = ThemeController(
        colorSchemeMode = when (colorMode) {
            1 -> ColorSchemeMode.Light
            2 -> ColorSchemeMode.Dark
            3 -> ColorSchemeMode.MonetSystem
            4 -> ColorSchemeMode.MonetLight
            5 -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.System
        },
        darkColors = amoledDark(),
        lightColors = lightColorScheme(),
        isDark = darkTheme,
    )
    val context = LocalContext.current
    LaunchedEffect(darkTheme) {
        val window = (context as? ComponentActivity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalDensity provides Density(density.density * state.appearance.scale, density.fontScale),
    ) {
        MiuixTheme(controller = controller) { XBlockerScreen(vm) }
    }
}

private fun date(time: Long): String = if (time == 0L) "尚未同步 · 使用内置词库" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

@Composable
private fun XBlockerScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val availableUpdate by vm.availableUpdate.collectAsStateWithLifecycle()
    val updateDownload by vm.updateDownload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    // SukiSU MainActivity/NavDisplay pattern: main tabs share one root entry;
    // theme settings are pushed above it. Persist both across recreation.
    var backStack by rememberSaveable { mutableStateOf(listOf(0)) }
    fun navigateBack() {
        if (backStack.size > 1) backStack = backStack.dropLast(1)
    }
    var editor by rememberSaveable { mutableStateOf("") }
    var editorText by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    var showRejected by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showLogDialog by rememberSaveable { mutableStateOf(false) }
    var selectedUpdateSource by rememberSaveable { mutableStateOf(UpdateSource.GITHUB.name) }
    var pendingNotificationTarget by rememberSaveable { mutableStateOf("") }
    val pages = listOf("概览", "规则", "记录", "设置", "主题设置", "关于", "运行诊断")
    val icons = listOf(Icons.Rounded.Cottage, Icons.AutoMirrored.Rounded.Rule, Icons.Rounded.History, Icons.Rounded.Settings)
    fun openUrl(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { vm.message("没有可用的应用打开此链接") } }
    fun edit(kind: String) { editor = kind; editorText = if (kind == "白名单") state.settings.whitelist.joinToString("\n") else state.settings.customRules }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingNotificationTarget
        pendingNotificationTarget = ""
        if (granted) {
            if (target == "native") vm.setFluidCloud(true)
            if (target == "focus") vm.setFocusNotification(true)
        } else vm.message("需要通知权限才能显示实时拦截状态")
    }
    fun toggleFluidCloud(on: Boolean) {
        if (!on) { vm.setFluidCloud(false); return }
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            pendingNotificationTarget = "native"
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.setFluidCloud(true)
    }
    fun toggleFocusNotification(on: Boolean) {
        if (!on) { vm.setFocusNotification(false); return }
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            pendingNotificationTarget = "focus"
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.setFocusNotification(true)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
                        val chars = CharArray(64_001); var count = 0
                        while (count < chars.size) { val n = reader.read(chars, count, chars.size - count); if (n < 0) break; count += n }
                        require(count <= 64_000) { "文件超过 64,000 字符" }; String(chars, 0, count)
                    }
                }
                editor = "自定义词库"; editorText = text
            }.onFailure { vm.message("导入失败：${it.message}") }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(state.settings.customRules) } } }
                .onSuccess { vm.message("自定义词库已导出") }.onFailure { vm.message("导出失败：${it.message}") }
        }
    }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) { Toast.makeText(context, state.message, Toast.LENGTH_LONG).show(); vm.message("") }
    }
    val options = state.appearance
    val usePredictiveBack = options.predictiveBack && Build.VERSION.SDK_INT >= 34
    val listStates = List(pages.size) { rememberLazyListState() }
    val scrollBehaviors = List(pages.size) { MiuixScrollBehavior() }
    val pageContent: @Composable (Int, Modifier) -> Unit = { page, pageModifier ->
    val scrollBehavior = scrollBehaviors[page]
    val backdrop = rememberBlurBackdrop(options.blur)
    val surfaceColor = MiuixTheme.colorScheme.surface
    val glassBackdrop = rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }
    val glassEnabled = options.blur && Build.VERSION.SDK_INT >= 33
    Scaffold(
        modifier = pageModifier,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = if (page == 0) "XBlocker" else pages[page],
                    color = if (backdrop != null) Color.Transparent else surfaceColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (page >= 4) IconButton(onClick = { navigateBack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回设置", tint = MiuixTheme.colorScheme.onSurface)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (page < 4) {
                if (options.floatingBar) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        if (Build.VERSION.SDK_INT < 33) {
                            PlainFloatingBar(page, pages, icons) { selectedPage = it }
                        } else {
                            FloatingBottomBar(
                                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                                selectedIndex = { page }, onSelected = { selectedPage = it },
                                backdrop = glassBackdrop, tabsCount = 4, isBlurEnabled = glassEnabled, isGlassEnabled = options.liquidGlass,
                            ) {
                                icons.forEachIndexed { index, icon ->
                                    FloatingBottomBarItem(
                                        onClick = { selectedPage = index },
                                        modifier = Modifier.semantics { selected = page == index },
                                    ) {
                                        // Glass renders a tinted second copy under the moving pill.
                                        val tint = if (!glassEnabled && page == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                                        Icon(icon, null, tint = tint)
                                        Text(pages[index], fontSize = 11.sp, lineHeight = 14.sp, color = tint, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    BlurredBar(backdrop) {
                        NavigationBar(color = if (backdrop != null) Color.Transparent else surfaceColor) {
                            icons.forEachIndexed { index, icon ->
                                NavigationBarItem(modifier = Modifier.weight(1f), selected = page == index, onClick = { selectedPage = index }, icon = icon, label = pages[index])
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!state.ready) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("正在读取配置…") }
        else Box(Modifier.fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .then(if (options.floatingBar && glassEnabled) Modifier.layerBackdrop(glassBackdrop) else Modifier)) {
            LazyColumn(
                state = listStates[page],
                modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + if (page == 4) 0.dp else 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    0 -> {
                        item { OverviewStatus(state, onHistory = { selectedPage = 2 }, onRules = { selectedPage = 1 }) }
                        item {
                            Card {
                                SuperSwitch(title = "启用过滤", summary = "在 X 中隐藏匹配的垃圾内容", checked = state.settings.enabled, onCheckedChange = { value -> vm.update { it.copy(enabled = value) } })
                                BasicComponent(title = "数据入口", summary = state.diagnostics.optString("adapter").ifEmpty { "等待 X 启动" })
                                BasicComponent(title = "本次 X 进程", summary = "解析 ${state.diagnostics.optLong("responses")} 次 · 移除 ${state.diagnostics.optLong("blocked")} 条")
                                BasicComponent(title = "系统", summary = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
                            }
                        }
                        item {
                            Card {
                                BasicComponent(title = "拦截记录", summary = "最近 ${state.history.size} 条 · 最多保留 200 条", onClick = { selectedPage = 2 }, endActions = { Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                                BasicComponent(title = "云端词库", summary = date(state.lastSync), onClick = { selectedPage = 1 }, endActions = { Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                            }
                        }
                        item {
                            Card {
                                BasicComponent(title = "打开 X", summary = "开始浏览，垃圾内容将被自动隐藏", onClick = {
                                    val launch = context.packageManager.getLaunchIntentForPackage("com.twitter.android")
                                    if (launch != null) context.startActivity(launch) else vm.message("未安装 X")
                                }, endActions = { Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                            }
                        }
                        if (state.syncError.isNotBlank()) item { Notice(state.syncError) }
                    }
                    1 -> {
                        item { SmallTitle("云端词库", insideMargin = sectionTitleMargin) }
                        item {
                            Card {
                                SuperSwitch(title = "使用云端规则", summary = "amahteru / x-comment-blocker", checked = state.settings.cloudEnabled, onCheckedChange = { value -> vm.update { it.copy(cloudEnabled = value) } })
                                BasicComponent(title = "最近同步", summary = date(state.lastSync), endActions = {
                                    TextButton(if (state.syncing) "同步中…" else "同步", onClick = vm::sync, enabled = !state.syncing && state.settings.cloudEnabled)
                                })
                            }
                        }
                        item {
                            Card {
                                state.engine.categories.forEach { (category, count) ->
                                    SuperSwitch(title = category, summary = "$count 条规则", checked = category !in state.settings.disabledCategories,
                                        enabled = state.settings.cloudEnabled, onCheckedChange = { on -> vm.update { it.copy(disabledCategories = if (on) it.disabledCategories - category else it.disabledCategories + category) } })
                                }
                            }
                        }
                        if (state.syncError.isNotBlank()) item { Notice(state.syncError) }
                        if (state.engine.rejected.isNotEmpty()) item {
                            Card { BasicComponent(title = "${state.engine.rejected.size} 条规则未启用", summary = "查看当前不兼容的正则规则", onClick = { showRejected = true }) }
                        }
                        item { SmallTitle("我的规则", insideMargin = sectionTitleMargin) }
                        item {
                            Card {
                                BasicComponent(title = "自定义词库", summary = "${RuleParser.parse(state.settings.customRules).size} 条 · 每行一个词或 /正则/i", onClick = { edit("自定义词库") }, endActions = { Text("编辑 ›") })
                                BasicComponent(title = "白名单", summary = "${state.settings.whitelist.size} 个账号 · 按 @用户名精确匹配", onClick = { edit("白名单") }, endActions = { Text("编辑 ›") })
                                BasicComponent(title = "规则测试", summary = "输入内容，查看是否命中及命中原因", onClick = { preview = true }, endActions = { Text("测试 ›") })
                            }
                        }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton("导入词库", onClick = { import.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.weight(1f))
                            TextButton("导出词库", onClick = { export.launch("xblocker-keywords.txt") }, modifier = Modifier.weight(1f))
                        } }
                        item { Notice("云端词库每 6 小时尝试更新，时间受系统后台调度影响。导入后先预览编辑，再保存。") }
                    }
                    2 -> {
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallTitle("最近 ${state.history.size} 条 / 最多 200 条", insideMargin = sectionTitleMargin)
                            TextButton("清空", onClick = { confirmClear = true }, enabled = state.history.isNotEmpty())
                        } }
                        if (state.history.isEmpty()) item {
                            Card(insideMargin = PaddingValues(28.dp)) {
                                Text("还没有拦截记录", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(10.dp))
                                Text("启用模块后浏览 X，命中规则的条目会出现在这里。记录不保存推文正文。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        items(state.history) { event ->
                            Card(insideMargin = PaddingValues(20.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(event.optString("handle").let { if (it.isEmpty()) "推广条目" else "@$it" }, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text(date(event.optLong("time")), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("${event.optString("category")} · ${event.optString("reason")}", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(event.optString("rule"), fontSize = 14.sp, maxLines = 3)
                                val handle = event.optString("handle")
                                if (handle.isNotBlank()) TextButton(if (handle.lowercase() in state.settings.whitelist) "已在白名单" else "加入白名单", enabled = handle.lowercase() !in state.settings.whitelist,
                                    onClick = { vm.update { it.copy(whitelist = it.whitelist + RuleParser.handle(handle)) }; vm.message("已加入白名单") }, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    3 -> settingsItems(
                        state = state, vm = vm,
                        onOpenTheme = { if (backStack.size == 1) backStack = backStack + 4 },
                        onOpenDiagnostics = { if (backStack.size == 1) backStack = backStack + 6 },
                        onOpenAbout = { if (backStack.size == 1) backStack = backStack + 5 },
                        onToggleFluidCloud = ::toggleFluidCloud,
                        onToggleFocusNotification = ::toggleFocusNotification,
                        onSendLog = { showLogDialog = true },
                    )
                    6 -> {
                        item {
                            Card {
                                BasicComponent(title = "X 连接", summary = when {
                                    state.diagnostics.optLong("lastSeen") != 0L -> "最近回报 ${date(state.diagnostics.optLong("lastSeen"))} · PID ${state.diagnostics.optInt("pid")}"
                                    state.marker.optLong("lastSeen") != 0L -> "未收到回报 · 激活标记 ${date(state.marker.optLong("lastSeen"))}"
                                    else -> "尚未收到模块回报"
                                })
                                BasicComponent(title = "数据入口", summary = state.diagnostics.optString("adapter").ifEmpty { "等待 X 启动" })
                                BasicComponent(title = "本次 X 进程", summary = "解析 ${state.diagnostics.optLong("responses")} 次 · 识别时间线 ${state.diagnostics.optLong("seen")} 次 · 移除 ${state.diagnostics.optLong("filtered")} 条")
                                if (state.diagnostics.optString("error").isNotBlank()) BasicComponent(title = "适配提示", summary = state.diagnostics.optString("error"))
                                if (state.marker.optLong("lastSeen") != 0L) BasicComponent(title = "激活标记", summary = buildString {
                                    append(when (state.marker.optString("phase")) {
                                        "started" -> "已启动"
                                        "init-failed" -> "初始化失败"
                                        "bridge-failed" -> if (state.marker.optBoolean("fallback")) "回报受阻 · 共享配置回退生效" else "回报受阻 · 无可用配置来源"
                                        else -> state.marker.optString("phase")
                                    })
                                    append(" · 首次 ${date(state.marker.optLong("firstSeen"))}")
                                    if (state.marker.optString("version").isNotBlank()) append(" · X ${state.marker.optString("version")}")
                                    if (state.marker.optString("error").isNotBlank()) append(" · ${state.marker.optString("error").take(120)}")
                                })
                            }
                        }
                        if (state.diagnostics.optLong("lastSeen") == 0L) item { Notice("未收到回报时依次检查：LSPosed 中已启用模块并勾选 X 作用域；更改后强行停止 X 再打开。上方激活标记也为空时，说明模块未被框架加载。") }
                        item { Notice("更换 X 版本后，请检查数据入口和时间线计数。已经缓存的内容需重新刷新；开关和规则修改约 5 秒生效。") }
                    }
                    4 -> appearanceItems(state, vm)
                }
            }
        }
    }
    }
    // One popup host across navigation entries keeps dialogs and export work mounted.
    Scaffold { _ ->
        // Adapted from SukiSU-Ultra v4.1.3 MainActivity.kt (0ca744a), GPL-3.0.
        // Miuix owns gesture seeking, cancellation, settling, clipping and dimming.
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            onBack = ::navigateBack,
            entryProvider = entryProvider {
                entry(0) { pageContent(selectedPage, Modifier.fillMaxSize()) }
                entry(4) { pageContent(4, Modifier.fillMaxSize()) }
                entry(5) {
                    AboutScreenMiuix(
                        state = remember { AboutUiState() },
                        actions = AboutScreenActions(onBack = ::navigateBack, onOpenLink = { link ->
                            when (link) {
                                "xblocker:licenses" -> if (backStack.last() == 5) backStack = backStack + 7
                                "xblocker:privacy" -> if (backStack.last() == 5) backStack = backStack + 8
                                else -> openUrl(link)
                            }
                        }),
                        enableBlur = options.blur,
                    )
                }
                entry(6) { pageContent(6, Modifier.fillMaxSize()) }
                entry(7) { AboutDocumentScreen(privacy = false, onBack = ::navigateBack) }
                entry(8) { AboutDocumentScreen(privacy = true, onBack = ::navigateBack) }
            },
        )
        // Registered after NavDisplay so disabling prediction consumes the gesture
        // without seeking its transition, then performs a normal pop on completion.
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = backStack.size > 1 && !usePredictiveBack &&
                editor.isEmpty() && !preview && !showRejected && !confirmClear && !showLogDialog && availableUpdate == null,
            onBackCompleted = ::navigateBack,
        )
        // Keep overlays outside the entries: a transition must not mount each
        // dialog twice, and dismissing one must take precedence over popping a page.
        SuperDialog(show = editor.isNotEmpty(), title = editor, onDismissRequest = { editor = "" }) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (editor == "白名单") "每行一个 @用户名，不填写昵称。" else "每行一个关键词，或 /正则表达式/i。保存会替换现有自定义词库。", fontSize = 14.sp)
                TextField(value = editorText, onValueChange = { if (it.length <= 64_001) editorText = it }, label = "输入规则", minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", onClick = { editor = "" }, modifier = Modifier.weight(1f))
                    TextButton("保存", onClick = { if (vm.saveText(editor, editorText)) editor = "" }, modifier = Modifier.weight(1f))
                }
            }
        }
        SuperDialog(show = showRejected, title = "未启用的规则", summary = "Java 与 JavaScript 正则存在差异；以下规则已跳过。", onDismissRequest = { showRejected = false }) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.engine.rejected.forEach { Text("${it.text}\n${it.reason}", fontSize = 13.sp) }
                TextButton("知道了", onClick = { showRejected = false }, modifier = Modifier.fillMaxWidth())
            }
        }
        SuperDialog(show = confirmClear, title = "清空本地记录？", summary = "同时重置累计拦截数量，规则不受影响。", onDismissRequest = { confirmClear = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", onClick = { confirmClear = false }, modifier = Modifier.weight(1f))
                TextButton("清空", onClick = { vm.clearHistory(); confirmClear = false }, modifier = Modifier.weight(1f))
            }
        }
        PreviewDialog(preview, state, onDismiss = { preview = false })
        SendLogDialog(showLogDialog, state, onDismissRequest = { showLogDialog = false })
        SuperDialog(show = availableUpdate != null, title = "发现新版本 ${availableUpdate?.version.orEmpty()}",
            onDismissRequest = vm::dismissUpdate) {
            val selectedSource = UpdateSource.valueOf(selectedUpdateSource)
            val downloading = updateDownload is UpdateDownloadState.Downloading
            val sourceLocked = downloading || updateDownload is UpdateDownloadState.Ready
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(availableUpdate?.notes?.ifBlank { "新版本已发布，可直接在应用内下载并请求系统安装。" }.orEmpty())
                OverlaySpinnerPreference(
                    title = "下载源",
                    summary = "选择更新包下载服务器",
                    items = listOf(
                        DropdownItem(UpdateSource.GITHUB.label, summary = "GitHub 官方发布服务器"),
                        DropdownItem(UpdateSource.GH_DPIK_TOP.label, summary = "网络受限时可尝试的镜像站"),
                    ),
                    selectedIndex = selectedSource.ordinal,
                    enabled = !sourceLocked,
                    onSelectedIndexChange = { index ->
                        selectedUpdateSource = UpdateSource.entries[index].name
                    },
                )
                when (val state = updateDownload) {
                    is UpdateDownloadState.Downloading -> Text("正在从 ${state.source.label} 下载：${downloadProgress(state.received, state.total)}",
                        color = MiuixTheme.colorScheme.primary)
                    is UpdateDownloadState.Failed -> Text("下载失败：${state.reason}", color = MiuixTheme.colorScheme.error)
                    is UpdateDownloadState.Ready -> Text("更新包已下载，可以请求系统安装。", color = MiuixTheme.colorScheme.primary)
                    UpdateDownloadState.Idle -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("稍后", onClick = vm::dismissUpdate, enabled = !downloading, modifier = Modifier.weight(1f))
                    if (updateDownload is UpdateDownloadState.Ready) {
                        TextButton("请求安装", onClick = vm::installDownloaded, modifier = Modifier.weight(1f))
                    } else {
                        TextButton(if (updateDownload is UpdateDownloadState.Failed) "重试下载" else "下载更新",
                            onClick = { vm.downloadUpdate(selectedSource) }, enabled = !downloading, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun downloadProgress(received: Long, total: Long): String {
    if (total > 0L) return "${(received * 100L / total).coerceIn(0L, 100L)}%"
    return "${received / (1024L * 1024L)} MB"
}

private val sectionTitleMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun PreviewDialog(show: Boolean, state: UiState, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf(true) }
    var result by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    SuperDialog(show = show, title = "规则测试", onDismissRequest = onDismiss) {
        Column(Modifier.imePadding().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = text, onValueChange = { text = it.take(32_768); result = "" }, label = "评论或推文正文", minLines = 2, maxLines = 4)
            TextField(value = name, onValueChange = { name = it.take(512); result = "" }, label = "昵称（可选）", singleLine = true)
            TextField(value = handle, onValueChange = { handle = it.take(128); result = "" }, label = "@用户名（可选）", singleLine = true)
            SuperSwitch(title = "这是一条回复", checked = reply, onCheckedChange = { reply = it; result = "" })
            if (result.isNotEmpty()) Text(result, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
            TextButton("检查是否命中", onClick = { scope.launch {
                val hit = withContext(Dispatchers.Default) { state.engine.match(Tweet("preview", text, name, handle, reply)) }
                result = hit?.let { "将被过滤 · ${it.reason}\n${it.rule}" } ?: "将被保留（包含总开关、回复范围和白名单判断）"
            } }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun Notice(text: String) { Text(text, modifier = Modifier.padding(horizontal = 6.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
