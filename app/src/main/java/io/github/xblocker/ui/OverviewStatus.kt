// Adapted from SukiSU-Ultra v4.1.3 HomeMiuix.kt StatusCard (GPL-3.0).
// XBlocker: real hook/enabled state and filtering statistics, accessible sizing.
package io.github.xblocker.ui

import io.github.xblocker.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OverviewStatus(state: UiState, onHistory: () -> Unit, onRules: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val colors = MiuixTheme.colorScheme
    val hooked = state.diagnostics.optInt("hooks") > 0
    // A recent marker means the hook runs in X but the report bridge is down; without any
    // marker at all the module was most likely never loaded by the framework.
    val markerFresh = System.currentTimeMillis() - state.marker.optLong("lastSeen") < 600_000
    // No successful report may have arrived after the marker, or the marker describes a
    // failure the bridge already recovered from.
    val bridgeFailed = state.marker.optString("phase") == "bridge-failed" &&
        state.marker.optLong("lastSeen") > state.diagnostics.optLong("lastSeen")
    val bridgeBlocked = markerFresh && bridgeFailed
    // OEMs kill X soon after it leaves the foreground, so a stale bridge-failed marker is
    // still the newest fact we have; "enable the module" would point users at the wrong fix.
    val bridgeBlockedStale = !markerFresh && bridgeFailed
    val active = !bridgeBlocked && hooked && state.settings.enabled &&
        System.currentTimeMillis() - state.diagnostics.optLong("lastSeen") < 20_000
    val monet = state.colorMode >= 3
    val dark = LocalDarkTheme.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.defaultColors(color = when {
                !active -> colors.surfaceContainer
                monet -> colors.secondaryContainer
                dark -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            }),
        ) {
            Box(Modifier.fillMaxSize().heightIn(min = 174.dp)) {
                Box(Modifier.matchParentSize().offset(38.dp, 45.dp), contentAlignment = Alignment.BottomEnd) {
                    Icon(
                        if (active) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
                        contentDescription = null, modifier = Modifier.size(170.dp),
                        tint = when {
                            !active -> colors.onSurfaceVariantActions.copy(alpha = 0.22f)
                            monet -> colors.primary.copy(alpha = 0.8f)
                            else -> Color(0xFF36D167)
                        },
                    )
                }
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        when { !state.settings.enabled -> resources.getString(R.string.filtering_paused); bridgeBlocked -> resources.getString(R.string.reporting_blocked); active -> resources.getString(R.string.filtering_active); bridgeBlockedStale -> resources.getString(R.string.reporting_blocked_last_session); hooked -> resources.getString(R.string.module_loaded); else -> resources.getString(R.string.waiting_for_x) },
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (bridgeFailed) resources.getString(R.string.check_hma_oss_hiding_rules_nsee_settings)
                        else if (hooked) "X ${state.diagnostics.optString("version")}"
                        else if (markerFresh) resources.getString(R.string.module_is_running_in_x_but_reporting)
                        else resources.getString(R.string.instructions_1_enable_the_module_and_select_x),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                    if (!hooked && !markerFresh && !bridgeFailed) Text(resources.getString(R.string.scope_changes_apply_only_to_newly_started), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(resources.getString(R.string.blocked), state.blocked.toString(), Modifier.weight(1f), onHistory)
            MetricCard(resources.getString(R.string.active_rules), state.engine.count.toString(), Modifier.weight(1f), onRules)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp), onClick = onClick) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
    }
}
