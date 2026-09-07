// Adapted from SukiSU-Ultra v4.1.3 SettingsMiuix.kt (0ca744a), GPL-3.0.
// Same grouped preferences, icon spacing and arrows, wired to XBlocker settings.
package io.github.xblocker.ui

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
                title = "启动时自动检查更新", summary = "打开应用后检查 GitHub 最新发布",
                startAction = { SettingsIcon(Icons.Filled.Update) },
                checked = state.autoUpdate,
                onCheckedChange = vm::setAutoUpdate,
            )
            OverlaySpinnerPreference(
                title = "更新渠道",
                summary = "接收的版本类型",
                startAction = { SettingsIcon(Icons.Rounded.RocketLaunch) },
                items = listOf(
                    DropdownItem("正式版", summary = "仅接收稳定发布"),
                    DropdownItem("预发布", summary = "提前获取 rc 测试版本"),
                ),
                selectedIndex = state.updateChannel,
                onSelectedIndexChange = vm::setUpdateChannel,
            )
        }
    }
    item {
        Card {
            ArrowPreference(
                title = "主题设置", summary = "主题、颜色与界面效果",
                startAction = { SettingsIcon(Icons.Rounded.Palette) }, onClick = onOpenTheme,
            )
        }
    }
    item {
        Card {
            SwitchPreference(
                title = "仅过滤回复", summary = "关闭后也检查首页、搜索推文",
                startAction = { SettingsIcon(Icons.Rounded.ChatBubble) },
                checked = state.settings.onlyReplies,
                onCheckedChange = { value -> vm.update { it.copy(onlyReplies = value) } },
            )
            SwitchPreference(
                title = "检查昵称和用户名", summary = "正文之外，也检查作者名称",
                startAction = { SettingsIcon(Icons.Rounded.Badge) },
                checked = state.settings.checkNames,
                onCheckedChange = { value -> vm.update { it.copy(checkNames = value) } },
            )
            SwitchPreference(
                title = "屏蔽推广广告", summary = "按推广标记移除，独立于词库和白名单",
                startAction = { SettingsIcon(Icons.Rounded.Block) },
                checked = state.settings.blockPromoted,
                onCheckedChange = { value -> vm.update { it.copy(blockPromoted = value) } },
            )
        }
    }
    item {
        Card {
            SwitchPreference(
                title = "原生超级岛 / 流体云",
                summary = "使用原生实时通知接口，不使用焦点通知；无需保留 XBlocker 后台卡片",
                startAction = { SettingsIcon(Icons.Rounded.NotificationsActive) },
                checked = state.fluidCloud, onCheckedChange = onToggleFluidCloud,
            )
            SwitchPreference(
                title = "焦点通知转换",
                summary = "独立发布小米焦点通知，需 HyperIsland 或系统支持",
                startAction = { SettingsIcon(Icons.Rounded.Notifications) },
                checked = state.focusNotification, onCheckedChange = onToggleFocusNotification,
            )
        }
    }
    item {
        Card {
            ArrowPreference(
                title = "运行诊断", summary = "模块连接、数据入口与拦截计数",
                startAction = { SettingsIcon(Icons.Rounded.Troubleshoot) }, onClick = onOpenDiagnostics,
            )
            ArrowPreference(
                title = "发送日志",
                startAction = { SettingsIcon(Icons.Rounded.BugReport) }, onClick = onSendLog,
            )
            ArrowPreference(
                title = "关于",
                startAction = { SettingsIcon(Icons.Rounded.ContactPage) }, onClick = onOpenAbout,
            )
            ArrowPreference(
                title = "检查更新", summary = "当前 v${io.github.xblocker.BuildConfig.VERSION_NAME} · ${if (state.autoUpdate) "启动时自动检查" else "仅手动检查"}",
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
