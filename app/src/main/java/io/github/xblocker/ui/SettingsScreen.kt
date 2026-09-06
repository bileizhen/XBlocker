// Adapted from SukiSU-Ultra v4.1.3 SettingsMiuix.kt (0ca744a), GPL-3.0.
// Same grouped preferences, icon spacing and arrows, wired to XBlocker settings.
package io.github.xblocker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
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
) {
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
                title = "流体云实时显示拦截",
                summary = "X 前台时显示拦截进度。ColorOS 16+ 使用胶囊，其他系统使用进度通知",
                startAction = { SettingsIcon(Icons.Rounded.NotificationsActive) },
                checked = state.fluidCloud, onCheckedChange = onToggleFluidCloud,
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
                title = "检查更新", summary = "当前 v${io.github.xblocker.BuildConfig.VERSION_NAME} · 启动时自动检查",
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
