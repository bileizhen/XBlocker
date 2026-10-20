package io.github.xblocker.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import io.github.xblocker.R
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent

internal enum class SspAdapterStatus { Waiting, Scanning, Active, Partial, Failed }

/** Policy invocations are not ad impressions and must never be presented as blocks. */
internal data class SspDiagnostics(
    val status: SspAdapterStatus,
    val blockedSlots: Long,
    val scanMode: String,
    val capabilities: Map<String, Boolean>,
)

internal fun sspDiagnostics(diagnostics: JSONObject): SspDiagnostics {
    val status = diagnostics.optString("sspAds")
    val scan = diagnostics.optJSONObject("sspScan")
    val items = scan?.optJSONObject("items")
    return SspDiagnostics(
        status = when {
            status == "scanning" -> SspAdapterStatus.Scanning
            status.startsWith("active:") -> SspAdapterStatus.Active
            status.startsWith("partial:") -> SspAdapterStatus.Partial
            status == "scan_failed" -> SspAdapterStatus.Failed
            else -> SspAdapterStatus.Waiting
        },
        blockedSlots = diagnostics.optLong("sspBlocked").coerceAtLeast(0),
        scanMode = scan?.optString("mode").orEmpty(),
        capabilities = if (items == null) emptyMap() else
            listOf("policy", "gate", "slots").associateWith { items.optBoolean(it) },
    )
}

@Composable
internal fun SspDiagnosticComponents(resources: Resources, diagnostics: JSONObject) {
    val presentation = sspDiagnostics(diagnostics)
    val status = when (presentation.status) {
        SspAdapterStatus.Waiting -> R.string.ssp_status_waiting
        SspAdapterStatus.Scanning -> R.string.ssp_status_scanning
        SspAdapterStatus.Active -> R.string.ssp_status_active
        SspAdapterStatus.Partial -> R.string.ssp_status_partial
        SspAdapterStatus.Failed -> R.string.ssp_status_failed
    }
    BasicComponent(title = resources.getString(R.string.ssp_video_ad_adapter), summary = buildString {
        append(resources.getString(status))
        when (presentation.scanMode) {
            "cache" -> append(" · ").append(resources.getString(R.string.ssp_source_cache))
            "dexkit" -> append(" · ").append(resources.getString(R.string.ssp_source_dexkit))
        }
    })
    if (presentation.capabilities.isNotEmpty()) {
        BasicComponent(title = resources.getString(R.string.ssp_adaptation_details), summary =
            listOf("policy" to R.string.ssp_policy_entry, "gate" to R.string.ssp_video_gate,
                "slots" to R.string.ssp_slot_filter).joinToString(" · ") { (key, title) ->
                resources.getString(R.string.ssp_capability_state, resources.getString(title),
                    resources.getString(if (presentation.capabilities[key] == true)
                        R.string.ssp_capability_found else R.string.ssp_capability_missing))
            })
    }
    BasicComponent(title = resources.getString(R.string.ssp_current_process),
        summary = resources.getString(R.string.ssp_blocked_slots, presentation.blockedSlots))
}
