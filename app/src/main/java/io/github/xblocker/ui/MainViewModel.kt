package io.github.xblocker.ui

import io.github.xblocker.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xblocker.core.FilterSettings
import io.github.xblocker.core.RuleEngine
import io.github.xblocker.core.RuleParser
import io.github.xblocker.data.CloudSync
import io.github.xblocker.data.AppUpdates
import io.github.xblocker.data.Repository
import io.github.xblocker.data.InstallResult
import io.github.xblocker.data.UpdateDownloadState
import io.github.xblocker.data.UpdateSource
import io.github.xblocker.data.XposedServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UiState(
    val ready: Boolean = false,
    val settings: FilterSettings = FilterSettings(),
    val cloud: String = "",
    val engine: RuleEngine = RuleEngine(FilterSettings(), ""),
    val lastSync: Long = 0,
    val syncError: String = "",
    val syncing: Boolean = false,
    val blocked: Long = 0,
    val diagnostics: JSONObject = JSONObject(),
    val marker: JSONObject = JSONObject(),
    val history: List<JSONObject> = emptyList(),
    val fluidCloud: Boolean = false,
    val focusNotification: Boolean = false,
    val colorMode: Int = 0,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val autoUpdate: Boolean = true,
    val updateChannel: Int = 0,
    val message: String = "",
)

/** Fluid cloud is on but system-side scopes are missing; serviceConnected selects the CTA. */
data class ScopePrompt(val missing: List<String>, val serviceConnected: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = io.github.xblocker.i18n.AppLanguage.context(getApplication<Application>())
    private val repo = Repository(app)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val update = MutableStateFlow<io.github.xblocker.core.AppRelease?>(null)
    val availableUpdate = update.asStateFlow()
    private val download = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownload = download.asStateFlow()
    private val checking = MutableStateFlow(false)
    val checkingUpdate = checking.asStateFlow()
    private val scopePromptState = MutableStateFlow<ScopePrompt?>(null)
    val scopePrompt = scopePromptState.asStateFlow()
    @Volatile private var scopeChecked = false
    init {
        viewModelScope.launch { while (true) { refresh(); delay(5000) } }
        if (repo.autoUpdate()) checkForUpdates(automatic = true)
        // The LSPosed binder may land after the first check; re-evaluate once it does.
        XposedServiceClient.onConnected { evaluateScopePrompt() }
    }

    /** Prompts once per app version when the fluid cloud route is on but scopes are missing. */
    private fun evaluateScopePrompt() {
        if (scopeChecked) return
        val version = io.github.xblocker.BuildConfig.VERSION_CODE
        if (repo.scopePromptVersion() >= version) return
        if (!mutable.value.fluidCloud) return
        val required = ScopeNotice.requiredScopes()
        if (XposedServiceClient.connected()) {
            val missing = required.filter { it !in XposedServiceClient.scope().orEmpty() }
            if (missing.isEmpty()) repo.setScopePromptShown(version)
            else { scopeChecked = true; scopePromptState.value = ScopePrompt(missing, true) }
        } else if (repo.hookMarker("hook@com.android.systemui") == null) {
            // No libxposed binder (legacy module / fork without delivery): a SystemUI hook
            // marker is the only proof the scope chain is live, so prompt when it is absent.
            scopeChecked = true
            scopePromptState.value = ScopePrompt(required, false)
        } else repo.setScopePromptShown(version)
    }

    fun requestScopes() {
        val prompt = scopePromptState.value ?: return
        dismissScopePrompt()
        if (prompt.serviceConnected) {
            message(context.getString(R.string.handle_the_lsposed_scope_request_in_your))
            XposedServiceClient.requestScope(prompt.missing) { approved, error ->
                message(if (approved != null) context.getString(R.string.scopes_authorized_restart_system_ui_as_instructed, approved.size)
                else context.getString(R.string.authorization_incomplete_you_can_select_scopes_manually, error?.let { io.github.xblocker.i18n.LocalizedText.resolve(context, it) }?.take(80)))
            }
        } else message(context.getString(R.string.open_lsposed_modules_xblocker_select_the_required))
    }

    /** OShin-style manual entry: request the complete scope list in one LSPosed confirmation. */
    fun requestAllScopes() {
        if (!XposedServiceClient.connected()) {
            message(context.getString(R.string.lsposed_service_is_unavailable_select_scopes_manually))
            return
        }
        val packages = listOf("com.twitter.android") + ScopeNotice.requiredScopes()
        message(context.getString(R.string.requesting_scopes_handle_the_lsposed_request_in, packages.size))
        XposedServiceClient.requestScope(packages) { approved, error ->
            message(if (approved != null) context.getString(R.string.scopes_authorized_restart_related_processes_as_instructed, approved.size)
            else context.getString(R.string.authorization_incomplete, error?.let { io.github.xblocker.i18n.LocalizedText.resolve(context, it) }?.take(80)))
        }
    }

    fun dismissScopePrompt() {
        repo.setScopePromptShown(io.github.xblocker.BuildConfig.VERSION_CODE)
        scopePromptState.value = null
    }

    fun dismissUpdate() {
        update.value = null
        download.value = UpdateDownloadState.Idle
    }

    fun setAutoUpdate(enabled: Boolean) {
        repo.setAutoUpdate(enabled)
        mutable.value = mutable.value.copy(autoUpdate = enabled)
    }

    fun setUpdateChannel(channel: Int) {
        if (channel == mutable.value.updateChannel) return
        repo.setUpdateChannel(channel)
        mutable.value = mutable.value.copy(updateChannel = channel)
        // Reflect the new channel immediately so switching to pre-release offers the RC.
        checkForUpdates()
    }

    fun checkForUpdates(automatic: Boolean = false) {
        if (checking.value) return
        checking.value = true
        viewModelScope.launch {
            try {
                val channel = io.github.xblocker.data.UpdateChannel.entries[repo.updateChannel()]
                val previous = update.value
                val next = withContext(Dispatchers.IO) { AppUpdates.check(channel) }
                update.value = next
                if (next?.version != previous?.version) download.value = UpdateDownloadState.Idle
                if (!automatic && next == null) message(context.getString(R.string.you_are_up_to_date_for_this))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!automatic) message(context.getString(R.string.unable_to_check_for_updates_try_again))
            } finally { checking.value = false }
        }
    }

    fun downloadUpdate(source: UpdateSource) {
        val release = update.value ?: return
        if (download.value is UpdateDownloadState.Downloading) return
        val app = getApplication<Application>()
        download.value = UpdateDownloadState.Downloading(source, 0L, -1L)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    AppUpdates.download(app, release, source) { received, total ->
                        download.value = UpdateDownloadState.Downloading(source, received, total)
                    }
                }
                download.value = UpdateDownloadState.Ready(source, file)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                download.value = UpdateDownloadState.Failed(source, error.message ?: context.getString(R.string.download_failed_title))
            }
        }
    }

    fun installDownloaded() {
        val ready = download.value as? UpdateDownloadState.Ready ?: return
        val app = getApplication<Application>()
        runCatching { AppUpdates.install(app, ready.file) }
            .onSuccess { result ->
                when (result) {
                    InstallResult.Started -> {
                        message(context.getString(R.string.system_installation_requested))
                        dismissUpdate()
                    }
                    InstallResult.PermissionRequired -> message(context.getString(R.string.allow_this_app_to_install_unknown_apps))
                }
            }
            .onFailure { message(context.getString(R.string.unable_to_start_installation,
                it.message?.let { error -> io.github.xblocker.i18n.LocalizedText.resolve(context, error) }
                    ?: context.getString(R.string.please_try_again))) }
    }
    private suspend fun refresh() {
        val next = withContext(Dispatchers.IO) {
            val settings = repo.settings(); val cloud = repo.cloud()
            val history = repo.history()
            val current = mutable.value
            current.copy(ready = true, settings = settings, cloud = cloud,
                engine = if (current.settings == settings && current.cloud == cloud) current.engine else RuleEngine(settings, cloud),
                lastSync = repo.lastSync(), syncError = repo.syncError(), blocked = repo.blocked(), diagnostics = repo.diagnostics(),
                marker = repo.marker(),
                fluidCloud = repo.fluidCloud(),
                focusNotification = repo.focusNotification(),
                colorMode = repo.colorMode(),
                appearance = repo.appearance(),
                autoUpdate = repo.autoUpdate(),
                updateChannel = repo.updateChannel(),
                history = (history.length() - 1 downTo 0).map { history.getJSONObject(it) })
        }
        mutable.value = next.copy(syncing = mutable.value.syncing, message = mutable.value.message,
            appearance = repo.appearance(), colorMode = repo.colorMode())
        if (next.ready) runCatching { evaluateScopePrompt() }
    }
    fun update(change: (FilterSettings) -> FilterSettings) { viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.save(change(repo.settings())) }; refresh()
    } }
    fun sync() {
        if (mutable.value.syncing) return
        mutable.value = mutable.value.copy(syncing = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { CloudSync.sync(repo) }
            refresh()
            mutable.value = mutable.value.copy(syncing = false, message = if (result.isSuccess) context.getString(R.string.cloud_rules_updated) else io.github.xblocker.i18n.LocalizedText.resolve(context, repo.syncError()))
        }
    }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }
    fun setColorMode(mode: Int) { viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.setColorMode(mode) }; refresh()
    } }
    fun setAppearance(value: AppearanceSettings) {
        // UI preferences are tiny; persist and publish together on the main thread so
        // rapid toggles cannot overwrite one another while a refresh is suspended.
        repo.setAppearance(value)
        mutable.value = mutable.value.copy(appearance = value)
    }
    fun setFluidCloud(enabled: Boolean) { viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.setFluidCloud(enabled) }
        val app = getApplication<Application>()
        runCatching {
            if (!enabled) app.getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(io.github.xblocker.fluid.FluidStatus.CAPSULE_ID)
        }.onFailure { message(context.getString(R.string.unable_to_update_live_status_notification, it.message?.take(80))) }
        refresh()
        if (enabled) runCatching { evaluateScopePrompt() }
    } }
    fun setFocusNotification(enabled: Boolean) { viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.setFocusNotification(enabled) }
        val app = getApplication<Application>()
        runCatching {
            if (!enabled) app.getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(io.github.xblocker.fluid.FocusStatus.NOTIFICATION_ID)
        }.onFailure { message(context.getString(R.string.unable_to_update_focus_notification, it.message?.take(80))) }
        refresh()
    } }
    fun clearHistory() { viewModelScope.launch { withContext(Dispatchers.IO) { repo.clearHistory() }; refresh() } }
    fun saveText(kind: String, text: String): Boolean {
        if (text.length > 64_000) { message(context.getString(R.string.content_cannot_exceed_64_000_characters)); return false }
        if (kind == "whitelist") {
            val handles = text.lineSequence().map(RuleParser::handle).filter { it.isNotEmpty() }.toSet()
            if (handles.any { !it.matches(Regex("[a-z0-9_]{1,15}")) }) { message(context.getString(R.string.enter_one_valid_username_per_line)); return false }
            update { it.copy(whitelist = handles) }
        } else {
            val check = RuleEngine(FilterSettings(cloudEnabled = false, customRules = text), "")
            if (check.rejected.isNotEmpty()) { message(context.getString(R.string.not_saved, io.github.xblocker.i18n.LocalizedText.resolve(context, check.rejected.first().reason))); return false }
            update { it.copy(customRules = text) }
        }
        message(context.getString(R.string.saved_changes_take_effect_in_x_in))
        return true
    }
}
