// Adapted from SukiSU-Ultra v4.1.3 AboutUiState.kt, GPL-3.0.
package io.github.xblocker.ui

import androidx.compose.runtime.Immutable
import io.github.xblocker.BuildConfig
import io.github.xblocker.data.CloudSync

@Immutable
data class AboutLink(val fullText: String, val url: String)

@Immutable
data class AboutUiState(
    val title: String = "关于",
    val appName: String = "XBlocker",
    val versionName: String = "v${BuildConfig.VERSION_NAME}",
    val links: List<AboutLink> = listOf(
        AboutLink("GitHub", "https://github.com/bileizhen/xblocker"),
        AboutLink("QQ交流群", "https://qm.qq.com/q/ngMDU5QcFM"),
        AboutLink("Telegram 频道", "https://t.me/bileizhen_XBlocker"),
        AboutLink("开源许可", "xblocker:licenses"),
        AboutLink("隐私说明", "xblocker:privacy"),
    ),
)

@Immutable
data class AboutScreenActions(val onBack: () -> Unit, val onOpenLink: (String) -> Unit)
