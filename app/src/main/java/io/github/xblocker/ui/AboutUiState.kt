// Adapted from SukiSU-Ultra v4.1.3 AboutUiState.kt, GPL-3.0.
package io.github.xblocker.ui

import io.github.xblocker.R

import androidx.compose.runtime.Immutable
import io.github.xblocker.BuildConfig

@Immutable
data class AboutLink(val fullText: String, val url: String)

@Immutable
class AboutUiState(context: android.content.Context) {
    val title: String = context.getString(R.string.about)
    val appName: String = "XBlocker"
    val versionName: String = "v${BuildConfig.VERSION_NAME}"
    val links: List<AboutLink> = listOf(
        AboutLink("GitHub", "https://github.com/bileizhen/xblocker"),
        AboutLink(context.getString(R.string.qq_community), "https://qm.qq.com/q/ngMDU5QcFM"),
        AboutLink(context.getString(R.string.telegram_group), "https://t.me/bileizhen_XBlocker_Group"),
        AboutLink(context.getString(R.string.open_source_licenses), "xblocker:licenses"),
        AboutLink(context.getString(R.string.privacy), "xblocker:privacy"),
    )
}

@Immutable
data class AboutScreenActions(val onBack: () -> Unit, val onOpenLink: (String) -> Unit)
