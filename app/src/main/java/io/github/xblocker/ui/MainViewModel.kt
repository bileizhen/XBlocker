package io.github.xblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xblocker.core.FilterSettings
import io.github.xblocker.core.RuleEngine
import io.github.xblocker.core.RuleParser
import io.github.xblocker.data.CloudSync
import io.github.xblocker.data.Repository
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
    val colorMode: Int = 0,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val autoUpdate: Boolean = true,
    val message: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val update = MutableStateFlow<io.github.xblocker.core.AppRelease?>(null)
    val availableUpdate = update.asStateFlow()
    private val checking = MutableStateFlow(false)
    val checkingUpdate = checking.asStateFlow()
    init {
        viewModelScope.launch { while (true) { refresh(); delay(5000) } }
        if (repo.autoUpdate()) checkForUpdates(automatic = true)
    }

    fun dismissUpdate() { update.value = null }

    fun setAutoUpdate(enabled: Boolean) {
        repo.setAutoUpdate(enabled)
        mutable.value = mutable.value.copy(autoUpdate = enabled)
    }

    fun checkForUpdates(automatic: Boolean = false) {
        if (checking.value) return
        checking.value = true
        viewModelScope.launch {
            try {
                update.value = withContext(Dispatchers.IO) { io.github.xblocker.data.AppUpdates.check() }
                if (!automatic && update.value == null) message("当前已是最新正式版")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!automatic) message("检查更新失败，请稍后重试")
            } finally { checking.value = false }
        }
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
                colorMode = repo.colorMode(),
                appearance = repo.appearance(),
                autoUpdate = repo.autoUpdate(),
                history = (history.length() - 1 downTo 0).map { history.getJSONObject(it) })
        }
        mutable.value = next.copy(syncing = mutable.value.syncing, message = mutable.value.message,
            appearance = repo.appearance(), colorMode = repo.colorMode())
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
            mutable.value = mutable.value.copy(syncing = false, message = if (result.isSuccess) "云端词库已更新" else repo.syncError())
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
        runCatching { if (enabled) io.github.xblocker.fluid.FluidCloudService.start(app) else io.github.xblocker.fluid.FluidCloudService.stop(app) }
            .onFailure { message("实时状态服务启动失败：${it.message?.take(80)}") }
        refresh()
    } }
    fun clearHistory() { viewModelScope.launch { withContext(Dispatchers.IO) { repo.clearHistory() }; refresh() } }
    fun saveText(kind: String, text: String): Boolean {
        if (text.length > 64_000) { message("内容不能超过 64,000 字符"); return false }
        if (kind == "白名单") {
            val handles = text.lineSequence().map(RuleParser::handle).filter { it.isNotEmpty() }.toSet()
            if (handles.any { !it.matches(Regex("[a-z0-9_]{1,15}")) }) { message("请每行填写一个有效的 @用户名"); return false }
            update { it.copy(whitelist = handles) }
        } else {
            val check = RuleEngine(FilterSettings(cloudEnabled = false, customRules = text), "")
            if (check.rejected.isNotEmpty()) { message("未保存：${check.rejected.first().reason}"); return false }
            update { it.copy(customRules = text) }
        }
        message("已保存，X 中约 5 秒后生效")
        return true
    }
}
