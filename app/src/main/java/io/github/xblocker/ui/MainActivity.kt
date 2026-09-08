package io.github.xblocker.ui

import io.github.xblocker.R

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
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(io.github.xblocker.i18n.AppLanguage.context(newBase))
    }

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
    val resources = androidx.compose.ui.platform.LocalResources.current
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

private fun date(resources: android.content.res.Resources, time: Long): String = if (time == 0L) resources.getString(R.string.not_synced_using_bundled_rules) else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, resources.configuration.locales[0]).format(Date(time))

@Composable
private fun XBlockerScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val availableUpdate by vm.availableUpdate.collectAsStateWithLifecycle()
    val updateDownload by vm.updateDownload.collectAsStateWithLifecycle()
    val scopePrompt by vm.scopePrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
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
    val pages = listOf(resources.getString(R.string.overview), resources.getString(R.string.rules), resources.getString(R.string.history), resources.getString(R.string.settings), resources.getString(R.string.appearance), resources.getString(R.string.about), resources.getString(R.string.diagnostics))
    val icons = listOf(Icons.Rounded.Cottage, Icons.AutoMirrored.Rounded.Rule, Icons.Rounded.History, Icons.Rounded.Settings)
    fun openUrl(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { vm.message(resources.getString(R.string.no_app_available_to_open_this_link)) } }
    fun edit(kind: String) { editor = kind; editorText = if (kind == "whitelist") state.settings.whitelist.joinToString("\n") else state.settings.customRules }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingNotificationTarget
        pendingNotificationTarget = ""
        if (granted) {
            if (target == "native") vm.setFluidCloud(true)
            if (target == "focus") vm.setFocusNotification(true)
        } else vm.message(resources.getString(R.string.notification_permission_is_required_to_show_live))
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
                        require(count <= 64_000) { resources.getString(R.string.file_exceeds_64_000_characters) }; String(chars, 0, count)
                    }
                }
                editor = "custom"; editorText = text
            }.onFailure { vm.message(resources.getString(R.string.import_failed, it.message)) }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(state.settings.customRules) } } }
                .onSuccess { vm.message(resources.getString(R.string.custom_rules_exported)) }.onFailure { vm.message(resources.getString(R.string.export_failed, it.message)) }
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
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, resources.getString(R.string.back_settings), tint = MiuixTheme.colorScheme.onSurface)
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
        if (!state.ready) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(resources.getString(R.string.loading_configuration)) }
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
                                SuperSwitch(title = resources.getString(R.string.enable_filtering), summary = resources.getString(R.string.hide_matching_unwanted_content_in_x), checked = state.settings.enabled, onCheckedChange = { value -> vm.update { it.copy(enabled = value) } })
                                BasicComponent(title = resources.getString(R.string.data_adapter), summary = state.diagnostics.optString("adapter").ifEmpty { resources.getString(R.string.waiting_for_x_to_start) })
                                BasicComponent(title = resources.getString(R.string.current_x_process), summary = resources.getString(R.string.parsed_removed, state.diagnostics.optLong("responses"), state.diagnostics.optLong("blocked")))
                                BasicComponent(title = resources.getString(R.string.system), summary = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
                            }
                        }
                        item {
                            Card {
                                BasicComponent(title = resources.getString(R.string.block_history), summary = resources.getString(R.string.latest_records_up_to_200_stored, state.history.size), onClick = { selectedPage = 2 }, endActions = { Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                                BasicComponent(title = resources.getString(R.string.cloud_rules), summary = date(resources, state.lastSync), onClick = { selectedPage = 1 }, endActions = { Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                            }
                        }
                        item {
                            Card {
                                BasicComponent(title = resources.getString(R.string.open_x), summary = resources.getString(R.string.start_browsing_to_automatically_hide_unwanted_content), onClick = {
                                    val launch = context.packageManager.getLaunchIntentForPackage("com.twitter.android")
                                    if (launch != null) context.startActivity(launch) else vm.message(resources.getString(R.string.x_is_not_installed))
                                }, endActions = { Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                            }
                        }
                        if (state.syncError.isNotBlank()) item { Notice(io.github.xblocker.i18n.LocalizedText.resolve(context, state.syncError)) }
                    }
                    1 -> {
                        item { SmallTitle(resources.getString(R.string.cloud_rules), insideMargin = sectionTitleMargin) }
                        item {
                            Card {
                                SuperSwitch(title = resources.getString(R.string.use_cloud_rules), summary = "amahteru / x-comment-blocker", checked = state.settings.cloudEnabled, onCheckedChange = { value -> vm.update { it.copy(cloudEnabled = value) } })
                                BasicComponent(title = resources.getString(R.string.last_sync), summary = date(resources, state.lastSync), endActions = {
                                    TextButton(if (state.syncing) resources.getString(R.string.syncing) else resources.getString(R.string.sync), onClick = vm::sync, enabled = !state.syncing && state.settings.cloudEnabled)
                                })
                            }
                        }
                        item {
                            Card {
                                state.engine.categories.forEach { (category, count) ->
                                    SuperSwitch(title = io.github.xblocker.i18n.LocalizedText.resolve(context, category), summary = resources.getString(R.string.rule_count, count), checked = category !in state.settings.disabledCategories,
                                        enabled = state.settings.cloudEnabled, onCheckedChange = { on -> vm.update { it.copy(disabledCategories = if (on) it.disabledCategories - category else it.disabledCategories + category) } })
                                }
                            }
                        }
                        if (state.syncError.isNotBlank()) item { Notice(io.github.xblocker.i18n.LocalizedText.resolve(context, state.syncError)) }
                        if (state.engine.rejected.isNotEmpty()) item {
                            Card { BasicComponent(title = resources.getString(R.string.disabled_rules, state.engine.rejected.size), summary = resources.getString(R.string.view_unsupported_regular_expressions), onClick = { showRejected = true }) }
                        }
                        item { SmallTitle(resources.getString(R.string.my_rules), insideMargin = sectionTitleMargin) }
                        item {
                            Card {
                                BasicComponent(title = resources.getString(R.string.custom_rules), summary = resources.getString(R.string.rules_one_keyword_or_regex_i_per, RuleParser.parse(state.settings.customRules).size), onClick = { edit("custom") }, endActions = { Text(resources.getString(R.string.edit)) })
                                BasicComponent(title = resources.getString(R.string.allowlist), summary = resources.getString(R.string.accounts_exact_username_matching, state.settings.whitelist.size), onClick = { edit("whitelist") }, endActions = { Text(resources.getString(R.string.edit)) })
                                BasicComponent(title = resources.getString(R.string.test_rules), summary = resources.getString(R.string.enter_content_to_see_whether_and_why), onClick = { preview = true }, endActions = { Text(resources.getString(R.string.test)) })
                            }
                        }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(resources.getString(R.string.import_rules), onClick = { import.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.weight(1f))
                            TextButton(resources.getString(R.string.export_rules), onClick = { export.launch("xblocker-keywords.txt") }, modifier = Modifier.weight(1f))
                        } }
                        item { Notice(resources.getString(R.string.cloud_rules_attempt_to_update_every_6)) }
                    }
                    2 -> {
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallTitle(resources.getString(R.string.latest_records_200_maximum, state.history.size), insideMargin = sectionTitleMargin)
                            TextButton(resources.getString(R.string.clear), onClick = { confirmClear = true }, enabled = state.history.isNotEmpty())
                        } }
                        if (state.history.isEmpty()) item {
                            Card(insideMargin = PaddingValues(28.dp)) {
                                Text(resources.getString(R.string.no_block_history_yet), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(10.dp))
                                Text(resources.getString(R.string.browse_x_with_the_module_enabled_to), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        items(state.history) { event ->
                            Card(insideMargin = PaddingValues(20.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(event.optString("handle").let { if (it.isEmpty()) resources.getString(R.string.promoted_entry) else "@$it" }, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text(date(resources, event.optLong("time")), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("${io.github.xblocker.i18n.LocalizedText.resolve(context, event.optString("category"))} · ${io.github.xblocker.i18n.LocalizedText.resolve(context, event.optString("reason"))}", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(event.optString("rule"), fontSize = 14.sp, maxLines = 3)
                                val handle = event.optString("handle")
                                if (handle.isNotBlank()) TextButton(if (handle.lowercase() in state.settings.whitelist) resources.getString(R.string.already_allowlisted) else resources.getString(R.string.add_to_allowlist), enabled = handle.lowercase() !in state.settings.whitelist,
                                    onClick = { vm.update { it.copy(whitelist = it.whitelist + RuleParser.handle(handle)) }; vm.message(resources.getString(R.string.added_to_allowlist)) }, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    3 -> settingsItems(context, resources,
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
                                BasicComponent(title = resources.getString(R.string.x_connection), summary = when {
                                    state.diagnostics.optLong("lastSeen") != 0L -> resources.getString(R.string.last_report_pid, date(resources, state.diagnostics.optLong("lastSeen")), state.diagnostics.optInt("pid"))
                                    state.marker.optLong("lastSeen") != 0L -> resources.getString(R.string.no_report_received_activation_marker, date(resources, state.marker.optLong("lastSeen")))
                                    else -> resources.getString(R.string.no_module_report_received_yet)
                                })
                                BasicComponent(title = resources.getString(R.string.data_adapter), summary = state.diagnostics.optString("adapter").ifEmpty { resources.getString(R.string.waiting_for_x_to_start) })
                                BasicComponent(title = resources.getString(R.string.current_x_process), summary = resources.getString(R.string.parsed_timelines_removed, state.diagnostics.optLong("responses"), state.diagnostics.optLong("seen"), state.diagnostics.optLong("filtered")))
                                if (state.diagnostics.optString("error").isNotBlank()) BasicComponent(title = resources.getString(R.string.compatibility_note), summary = io.github.xblocker.i18n.LocalizedText.resolve(context, state.diagnostics.optString("error")))
                                if (state.marker.optLong("lastSeen") != 0L) BasicComponent(title = resources.getString(R.string.activation_marker), summary = buildString {
                                    append(when (state.marker.optString("phase")) {
                                        "started" -> resources.getString(R.string.started)
                                        "init-failed" -> resources.getString(R.string.initialization_failed)
                                        "bridge-failed" -> if (state.marker.optBoolean("fallback")) resources.getString(R.string.reporting_blocked_shared_configuration_fallback_active) else resources.getString(R.string.reporting_blocked_no_configuration_source_available)
                                        else -> state.marker.optString("phase")
                                    })
                                    append(resources.getString(R.string.first_seen, date(resources, state.marker.optLong("firstSeen"))))
                                    if (state.marker.optString("version").isNotBlank()) append(" · X ${state.marker.optString("version")}")
                                    if (state.marker.optString("error").isNotBlank()) append(" · ${state.marker.optString("error").take(120)}")
                                })
                            }
                        }
                        if (state.diagnostics.optLong("lastSeen") == 0L) item { Notice(resources.getString(R.string.if_no_report_is_received_enable_the)) }
                        item { Notice(resources.getString(R.string.hma_oss_app_hiding_allow_x_to)) }
                        item { Notice(resources.getString(R.string.if_the_module_is_loaded_but_shows)) }
                        item { Notice(resources.getString(R.string.if_filtering_works_only_while_xblocker_remains)) }
                        item { Notice(resources.getString(R.string.after_changing_the_x_version_check_the)) }
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
                        state = AboutUiState(context),
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
        SuperDialog(show = editor.isNotEmpty(), title = if (editor == "whitelist") resources.getString(R.string.allowlist) else resources.getString(R.string.custom_rules), onDismissRequest = { editor = "" }) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (editor == "whitelist") resources.getString(R.string.one_username_per_line_not_a_display) else resources.getString(R.string.one_keyword_or_regular_expression_i_per), fontSize = 14.sp)
                TextField(value = editorText, onValueChange = { if (it.length <= 64_001) editorText = it }, label = resources.getString(R.string.enter_rules), minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(resources.getString(R.string.cancel), onClick = { editor = "" }, modifier = Modifier.weight(1f))
                    TextButton(resources.getString(R.string.save), onClick = { if (vm.saveText(editor, editorText)) editor = "" }, modifier = Modifier.weight(1f))
                }
            }
        }
        SuperDialog(show = showRejected, title = resources.getString(R.string.disabled_rules_title), summary = resources.getString(R.string.java_and_javascript_regular_expressions_differ_the), onDismissRequest = { showRejected = false }) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.engine.rejected.forEach { Text("${it.text}\n${io.github.xblocker.i18n.LocalizedText.resolve(context, it.reason)}", fontSize = 13.sp) }
                TextButton(resources.getString(R.string.got_it), onClick = { showRejected = false }, modifier = Modifier.fillMaxWidth())
            }
        }
        SuperDialog(show = confirmClear, title = resources.getString(R.string.clear_local_history), summary = resources.getString(R.string.also_resets_the_cumulative_block_count_rules), onDismissRequest = { confirmClear = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(resources.getString(R.string.cancel), onClick = { confirmClear = false }, modifier = Modifier.weight(1f))
                TextButton(resources.getString(R.string.clear), onClick = { vm.clearHistory(); confirmClear = false }, modifier = Modifier.weight(1f))
            }
        }
        val activePrompt = scopePrompt
        SuperDialog(show = activePrompt != null, title = resources.getString(R.string.scopes_required),
            summary = if (activePrompt?.serviceConnected == true) resources.getString(R.string.native_super_island_fluid_cloud_requires_the)
            else resources.getString(R.string.native_super_island_fluid_cloud_requires_the_124),
            onDismissRequest = vm::dismissScopePrompt) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                activePrompt?.missing?.forEach { Text("· ${ScopeNotice.label(context, it)}\n  $it", fontSize = 13.sp) }
                Text(resources.getString(R.string.after_authorization_lsposed_may_ask_you_to), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(resources.getString(R.string.not_now), onClick = vm::dismissScopePrompt, modifier = Modifier.weight(1f))
                    if (activePrompt?.serviceConnected == true) {
                        TextButton(resources.getString(R.string.authorize), onClick = vm::requestScopes, modifier = Modifier.weight(1f))
                    } else {
                        TextButton(resources.getString(R.string.open_lsposed), onClick = {
                            val launch = runCatching { context.packageManager.getLaunchIntentForPackage("org.lsposed.manager") }.getOrNull()
                            if (launch != null) runCatching { context.startActivity(launch) }
                                .onFailure { vm.message(resources.getString(R.string.unable_to_open_lsposed, it.message?.take(60))) }
                            else vm.message(resources.getString(R.string.lsposed_manager_was_not_found_open_it))
                            vm.requestScopes()
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        PreviewDialog(preview, state, onDismiss = { preview = false })
        SendLogDialog(showLogDialog, state, onDismissRequest = { showLogDialog = false })
        SuperDialog(show = availableUpdate != null, title = resources.getString(R.string.new_version, availableUpdate?.version.orEmpty()),
            onDismissRequest = vm::dismissUpdate) {
            val selectedSource = UpdateSource.valueOf(selectedUpdateSource)
            val downloading = updateDownload is UpdateDownloadState.Downloading
            val sourceLocked = downloading || updateDownload is UpdateDownloadState.Ready
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MarkdownText(availableUpdate?.notes.orEmpty().ifBlank { resources.getString(R.string.a_new_version_is_available_download_it) })
                OverlaySpinnerPreference(
                    title = resources.getString(R.string.download_source),
                    summary = resources.getString(R.string.choose_the_update_download_server),
                    items = listOf(
                        DropdownItem(resources.getString(UpdateSource.GITHUB.label), summary = resources.getString(R.string.official_github_release_server)),
                        DropdownItem(resources.getString(UpdateSource.GH_DPIK_TOP.label), summary = resources.getString(R.string.try_this_mirror_if_github_is_unreachable)),
                    ),
                    selectedIndex = selectedSource.ordinal,
                    enabled = !sourceLocked,
                    onSelectedIndexChange = { index ->
                        selectedUpdateSource = UpdateSource.entries[index].name
                    },
                )
                when (val state = updateDownload) {
                    is UpdateDownloadState.Downloading -> Text(resources.getString(R.string.downloading_from, resources.getString(state.source.label), downloadProgress(state.received, state.total)),
                        color = MiuixTheme.colorScheme.primary)
                    is UpdateDownloadState.Failed -> Text(resources.getString(R.string.download_failed, io.github.xblocker.i18n.LocalizedText.resolve(context, state.reason)), color = MiuixTheme.colorScheme.error)
                    is UpdateDownloadState.Ready -> Text(resources.getString(R.string.update_downloaded_and_ready_to_install), color = MiuixTheme.colorScheme.primary)
                    UpdateDownloadState.Idle -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(resources.getString(R.string.later), onClick = vm::dismissUpdate, enabled = !downloading, modifier = Modifier.weight(1f))
                    if (updateDownload is UpdateDownloadState.Ready) {
                        TextButton(resources.getString(R.string.request_installation), onClick = vm::installDownloaded, modifier = Modifier.weight(1f))
                    } else {
                        TextButton(if (updateDownload is UpdateDownloadState.Failed) resources.getString(R.string.retry_download) else resources.getString(R.string.download_update),
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
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf(true) }
    var result by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    SuperDialog(show = show, title = resources.getString(R.string.test_rules), onDismissRequest = onDismiss) {
        Column(Modifier.imePadding().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = text, onValueChange = { text = it.take(32_768); result = "" }, label = resources.getString(R.string.reply_or_tweet_text), minLines = 2, maxLines = 4)
            TextField(value = name, onValueChange = { name = it.take(512); result = "" }, label = resources.getString(R.string.display_name_optional), singleLine = true)
            TextField(value = handle, onValueChange = { handle = it.take(128); result = "" }, label = resources.getString(R.string.username_optional), singleLine = true)
            SuperSwitch(title = resources.getString(R.string.this_is_a_reply), checked = reply, onCheckedChange = { reply = it; result = "" })
            if (result.isNotEmpty()) Text(result, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
            TextButton(resources.getString(R.string.check_for_a_match), onClick = { scope.launch {
                val hit = withContext(Dispatchers.Default) { state.engine.match(Tweet("preview", text, name, handle, reply)) }
                result = hit?.let { resources.getString(R.string.will_be_filtered_n, io.github.xblocker.i18n.LocalizedText.resolve(context, it.reason), it.rule) } ?: resources.getString(R.string.will_be_kept_considering_the_main_switch)
            } }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun Notice(text: String) { Text(text, modifier = Modifier.padding(horizontal = 6.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
