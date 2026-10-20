package io.github.xblocker.data

import android.content.Context
import io.github.xblocker.BuildConfig
import org.json.JSONObject

class UpdateActivationStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("update_activation", Context.MODE_PRIVATE)
    fun read(): UpdateActivationState? = synchronized(lock) {
        prefs.getString("state", null)?.let { runCatching { UpdateActivationState.fromJson(JSONObject(it)) }.getOrNull() }
    }
    fun begin(token: String): Boolean = synchronized(lock) {
        if (read()?.token == token) return false
        val previousPid = Repository(context).diagnostics().optInt("pid")
        write(UpdateActivationState(token, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, previousPid = previousPid))
        true
    }
    fun update(token: String, change: (UpdateActivationState) -> UpdateActivationState) = synchronized(lock) {
        read()?.takeIf { it.token == token }?.let { write(change(it)) }
    }
    fun observeHost(report: JSONObject) = synchronized(lock) {
        // ModuleProvider has verified the caller UID; an exported broadcast is not activation proof.
        read()?.takeIf { it.acceptsHost(report) }?.let { write(it.copy(phase = ActivationPhase.ACTIVE, activePid = report.optInt("pid"))) }
    }
    private fun write(state: UpdateActivationState) {
        check(prefs.edit().putString("state", state.toJson().toString()).commit())
        if (BuildConfig.DEBUG) runCatching {
            java.io.File(context.filesDir, "update-status.json").writeText(state.toJson().apply { remove("token") }.toString())
        }
    }
    companion object { private val lock = Any() }
}
