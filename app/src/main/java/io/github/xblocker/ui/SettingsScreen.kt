// Adapted from SukiSU-Ultra v4.1.3 SettingsMiuix.kt (0ca744a), GPL-3.0.
// Same grouped preferences, icon spacing and arrows, wired to XBlocker settings.
package io.github.xblocker.ui

import io.github.xblocker.R

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.settingsItems(
    context: android.content.Context,
    resources: android.content.res.Resources,
    state: UiState,
    vm: MainViewModel,
    onOpenTheme: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onSendLog: () -> Unit,
    onToggleFluidCloud: (Boolean) -> Unit,
    onToggleFocusNotification: (Boolean) -> Unit,
) {
    item {
        Card {
            SwitchPreference(
                title = resources.getString(R.string.check_for_updates_on_launch), summary = resources.getString(R.string.check_the_latest_github_release_when_opening),
                startAction = { SettingsIcon(Icons.Filled.Update) },
                checked = state.autoUpdate,
                onCheckedChange = vm::setAutoUpdate,
            )
            OverlaySpinnerPreference(
                title = resources.getString(R.string.update_channel),
                summary = resources.getString(R.string.which_releases_to_receive),
                startAction = { SettingsIcon(Icons.Rounded.RocketLaunch) },
                items = listOf(
                    DropdownItem(resources.getString(R.string.stable), summary = resources.getString(R.string.receive_stable_releases_only)),
                    DropdownItem(resources.getString(R.string.pre_release), summary = resources.getString(R.string.get_early_access_to_release_candidates)),
                ),
                selectedIndex = state.updateChannel,
                onSelectedIndexChange = vm::setUpdateChannel,
            )
        }
    }
    item {
        Card {
            val tags = io.github.xblocker.i18n.AppLanguage.tags
            OverlaySpinnerPreference(
                title = resources.getString(R.string.language),
                summary = resources.getString(R.string.language_summary),
                startAction = { SettingsIcon(Icons.Rounded.Language) },
                items = listOf(
                    DropdownItem(resources.getString(R.string.follow_system)),
                    DropdownItem("English"),
                    DropdownItem("简体中文"),
                    DropdownItem("繁體中文"),
                ),
                selectedIndex = tags.indexOf(io.github.xblocker.i18n.AppLanguage.current(context)).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    (context as? android.app.Activity)?.let { io.github.xblocker.i18n.AppLanguage.set(it, tags[index]) }
                },
            )
            ArrowPreference(
                title = resources.getString(R.string.appearance), summary = resources.getString(R.string.theme_colors_and_interface_effects),
                startAction = { SettingsIcon(Icons.Rounded.Palette) }, onClick = onOpenTheme,
            )
        }
    }
    item {
        Card {
            SwitchPreference(
                title = resources.getString(R.string.filter_replies_only), summary = resources.getString(R.string.when_off_also_checks_home_and_search),
                startAction = { SettingsIcon(Icons.Rounded.ChatBubble) },
                checked = state.settings.onlyReplies,
                onCheckedChange = { value -> vm.update { it.copy(onlyReplies = value) } },
            )
            SwitchPreference(
                title = resources.getString(R.string.check_display_names_and_usernames), summary = resources.getString(R.string.also_match_against_author_names),
                startAction = { SettingsIcon(Icons.Rounded.Badge) },
                checked = state.settings.checkNames,
                onCheckedChange = { value -> vm.update { it.copy(checkNames = value) } },
            )
            SwitchPreference(
                title = resources.getString(R.string.block_promoted_ads), summary = resources.getString(R.string.remove_promoted_entries_independently_of_rules_and),
                startAction = { SettingsIcon(Icons.Rounded.Block) },
                checked = state.settings.blockPromoted,
                onCheckedChange = { value -> vm.update { it.copy(blockPromoted = value) } },
            )
        }
    }
    item {
        Card {
            SwitchPreference(
                title = resources.getString(R.string.native_super_island_fluid_cloud),
                summary = resources.getString(R.string.uses_the_native_live_notification_api_no),
                startAction = { SettingsIcon(Icons.Rounded.NotificationsActive) },
                checked = state.fluidCloud, onCheckedChange = onToggleFluidCloud,
            )
            SwitchPreference(
                title = resources.getString(R.string.focus_notification_conversion),
                summary = resources.getString(R.string.publish_separate_xiaomi_focus_notifications_requires_hyperisland),
                startAction = { SettingsIcon(Icons.Rounded.Notifications) },
                checked = state.focusNotification, onCheckedChange = onToggleFocusNotification,
            )
            ArrowPreference(
                title = resources.getString(R.string.request_all_scopes),
                summary = resources.getString(R.string.request_x_and_system_scopes_through_lsposed),
                startAction = { SettingsIcon(Icons.Rounded.Security) },
                onClick = vm::requestAllScopes,
            )
        }
    }
    item {
        Card {
            ArrowPreference(
                title = resources.getString(R.string.diagnostics), summary = resources.getString(R.string.module_connection_data_adapter_and_block_counts),
                startAction = { SettingsIcon(Icons.Rounded.Troubleshoot) }, onClick = onOpenDiagnostics,
            )
            ArrowPreference(
                title = resources.getString(R.string.share_logs),
                startAction = { SettingsIcon(Icons.Rounded.BugReport) }, onClick = onSendLog,
            )
            ArrowPreference(
                title = resources.getString(R.string.about),
                startAction = { SettingsIcon(Icons.Rounded.ContactPage) }, onClick = onOpenAbout,
            )
            ArrowPreference(
                title = resources.getString(R.string.check_for_updates), summary = resources.getString(R.string.current_v, io.github.xblocker.BuildConfig.VERSION_NAME, if (state.autoUpdate) resources.getString(R.string.check_on_launch) else resources.getString(R.string.manual_checks_only)),
                startAction = { SettingsIcon(Icons.Rounded.NotificationsActive) },
                onClick = { vm.checkForUpdates() },
            )
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground)
}
