// Adapted from SukiSU-Ultra v4.1.3 ColorPaletteScreenMiuix.kt (GPL-3.0).
// XBlocker: local state/actions, same theme groups and scale range, API guards.
package io.github.xblocker.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal fun LazyListScope.appearanceItems(state: UiState, vm: MainViewModel) {
    item {
        val options = state.appearance
        val monet = state.colorMode >= 3
        Spacer(Modifier.height(32.dp))
        ThemePreviewCardMiuix(
            isDark = LocalDarkTheme.current, miuixMonet = monet,
            enableFloatingBottomBar = options.floatingBar,
            enableFloatingBottomBarBlur = options.liquidGlass && options.blur && Build.VERSION.SDK_INT >= 33,
        )
        Spacer(Modifier.height(72.dp))
        TabRow(
            tabs = listOf("跟随系统", "浅色", "深色"),
            selectedTabIndex = state.colorMode % 3,
            onTabSelected = { vm.setColorMode(it + if (monet) 3 else 0) },
            height = 48.dp,
        )
        Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
            SuperSwitch(
                title = "启用 Monet 颜色", checked = monet,
                enabled = Build.VERSION.SDK_INT >= 31,
                summary = if (Build.VERSION.SDK_INT < 31) "需要 Android 12 或更高版本" else null,
                startAction = { PreferenceIcon(Icons.Rounded.Wallpaper) },
                onCheckedChange = { vm.setColorMode(state.colorMode % 3 + if (it) 3 else 0) },
            )
        }
        Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
            SuperSwitch(
                title = "模糊", summary = if (Build.VERSION.SDK_INT >= 33) "启用顶栏和底栏的模糊效果" else "需要 Android 13 或更高版本",
                checked = options.blur, enabled = Build.VERSION.SDK_INT >= 33,
                startAction = { PreferenceIcon(Icons.Rounded.BlurOn) },
                onCheckedChange = { vm.setAppearance(options.copy(blur = it)) },
            )
            SuperSwitch(
                title = "悬浮底栏", summary = "使用 Apple 风格的悬浮底栏",
                checked = options.floatingBar,
                startAction = { PreferenceIcon(Icons.Rounded.CallToAction) },
                onCheckedChange = { vm.setAppearance(options.copy(floatingBar = it)) },
            )
            SuperSwitch(
                title = "液态玻璃", summary = "启用悬浮底栏的液态玻璃效果",
                checked = options.liquidGlass, enabled = options.floatingBar && options.blur && Build.VERSION.SDK_INT >= 33,
                startAction = { PreferenceIcon(Icons.Rounded.WaterDrop) },
                onCheckedChange = { vm.setAppearance(options.copy(liquidGlass = it)) },
            )
        }
        Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
            SuperSwitch(
                title = "预测性返回手势", summary = "启用对预测性返回手势的支持",
                checked = options.predictiveBack, enabled = Build.VERSION.SDK_INT >= 34,
                startAction = { PreferenceIcon(Icons.AutoMirrored.Rounded.MenuOpen) },
                onCheckedChange = { vm.setAppearance(options.copy(predictiveBack = it)) },
            )
            var sliderValue by remember(options.scale) { mutableFloatStateOf(options.scale) }
            var showScaleDialog by rememberSaveable { mutableStateOf(false) }
            BasicComponent(
                title = "界面缩放", summary = "调整全局显示比例",
                startAction = { PreferenceIcon(Icons.Rounded.AspectRatio) },
                endActions = {
                    Text("${(sliderValue * 100).roundToInt()}%", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    Icon(Icons.Rounded.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                },
                onClick = { showScaleDialog = true },
                bottomAction = {
                    Slider(
                        value = sliderValue, onValueChange = { sliderValue = it },
                        onValueChangeFinished = { vm.setAppearance(options.copy(scale = sliderValue)) },
                        valueRange = 0.8f..1.1f, showKeyPoints = true,
                        keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f), magnetThreshold = 0.01f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    )
                },
            )
            OverlayDialog(show = showScaleDialog, title = "界面缩放", summary = "80% - 110%", onDismissRequest = { showScaleDialog = false }) {
                var input by remember(showScaleDialog) { mutableStateOf((options.scale * 100).roundToInt().toString()) }
                TextField(value = input, onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) input = it }, singleLine = true)
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", onClick = { showScaleDialog = false }, modifier = Modifier.weight(1f))
                    TextButton("确定", enabled = input.toIntOrNull() in 80..110, onClick = {
                        input.toIntOrNull()?.let { vm.setAppearance(options.copy(scale = it.coerceIn(80, 110) / 100f)) }
                        showScaleDialog = false
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PreferenceIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp), tint = MiuixTheme.colorScheme.onSurface)
}
