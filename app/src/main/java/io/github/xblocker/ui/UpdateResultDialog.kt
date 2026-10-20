package io.github.xblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import io.github.xblocker.R
import io.github.xblocker.data.ActivationPhase
import io.github.xblocker.data.UpdateActivationState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun UpdateResultDialog(state: UpdateActivationState?, onDismiss: () -> Unit, onRetry: () -> Unit) {
    if (state == null || state.dismissed) return
    val resources = LocalResources.current
    OverlayDialog(show = true, title = resources.getString(R.string.update_result_title), onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val scan = state.scan
            if (scan != null) {
                val total = scan.optInt("total")
                val success = scan.optInt("success")
                Text(resources.getString(R.string.update_scan_counts, total, success, (total - success).coerceAtLeast(0)))
                Text(resources.getString(R.string.update_scan_host, scan.optString("hostVersion"), scan.optLong("hostCode")))
                val source = when (scan.optString("mode")) {
                    "fast" -> R.string.update_scan_fast
                    "cache" -> R.string.update_scan_cache
                    "dexkit" -> R.string.update_scan_dexkit
                    else -> R.string.update_scan_failed
                }
                Text(resources.getString(source))
                scan.optJSONObject("media")?.let { media ->
                    Text(resources.getString(R.string.update_media_scan, media.optInt("success"), media.optInt("total")))
                }
                scan.optJSONObject("ssp")?.let { ssp ->
                    val totalSsp = ssp.optInt("total")
                    val successSsp = ssp.optInt("success")
                    Text(resources.getString(R.string.ssp_video_ad_adapter) + ": " +
                        resources.getString(R.string.update_scan_counts, totalSsp, successSsp, (totalSsp - successSsp).coerceAtLeast(0)))
                }
            }
            Text(resources.getString(R.string.update_scan_module, state.moduleVersion, state.moduleCode))
            val status = when (state.phase) {
                ActivationPhase.QUEUED -> R.string.update_activation_queued
                ActivationPhase.SCANNING -> R.string.update_activation_scanning
                ActivationPhase.READY -> R.string.update_activation_ready
                ActivationPhase.ACTIVE -> R.string.update_activation_active
                ActivationPhase.FAILED -> R.string.update_scan_failed
            }
            Text(resources.getString(status))
            if (state.phase == ActivationPhase.FAILED) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(resources.getString(R.string.update_activation_retry)) }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(resources.getString(R.string.close)) }
        }
    }
}
