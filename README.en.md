<div align="center">
  <img src=".github/img/icon-512.png" width="96" alt="XBlocker">
  <h1>XBlocker</h1>
  <p>Less noise in your X timeline.</p>

  <p><a href="README.md">简体中文</a> | <strong>English</strong></p>

  [![License](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)
  [![Android 9+](https://img.shields.io/badge/Android-9%2B-blue.svg)](https://developer.android.com/about/versions/pie)
  [![LSPosed](https://img.shields.io/badge/LSPosed-API_101-orange.svg)](https://lsposed.org)
  [![GitHub](https://img.shields.io/badge/Author-bileizhen-blue)](https://github.com/bileizhen)

  <p>
    <a href="https://github.com/bileizhen/XBlocker/releases/latest">Download the latest release</a>
  </p>
</div>

XBlocker is an LSPosed filtering module for the native X client on Android. It matches rules locally to hide spam replies, promoted content and selected categories.

## Features

- **Fine-grained filtering**: Filters replies by default, with optional filtering for Home and Search timelines. Display names, usernames, promoted content and rule categories can be controlled independently.
- **Custom rules**: Keywords, `/regex/flags`, an @username allowlist, import/export and rule testing.
- **Cloud rules**: Syncs with [x-comment-blocker](https://github.com/amahteru/x-comment-blocker), includes a bundled offline snapshot, and supports manual and scheduled updates.
- **Support for multiple X versions**: Automatically selects a filtering adapter based on the host interfaces rather than a single version number. Unrecognized structures pass through unchanged.
- **In-app updates**: Automatically checks GitHub stable releases on launch, with manual checks also available. Download from GitHub or the `gh.dpik.top` mirror, then request installation by the system.
- **Diagnostics and history**: View the module connection, data adapter, block counts and the latest 200 block records. Export diagnostics as a ZIP archive.
- **Live blocking status**: The native channel uses Android 16 live notification APIs. The system decides whether to display Xiaomi Super Island or OPlus Fluid Cloud; this channel does not inject focus notification parameters. Focus notification conversion has a separate switch.
- **No persistent background monitor**: The X process reports and publishes live status directly, and the system clears it after a timeout. There is no need to keep XBlocker in the recent apps list.
- **Miuix interface**: Light and dark themes, Monet colors, blur, Liquid Glass, predictive back gestures and global display scaling.
- **Multiple languages**: Simplified Chinese, Traditional Chinese, English and a follow-system option. Switch languages in Settings, or use the system's per-app language settings on Android 13 and later. See [I18N.md](I18N.md) for translation maintenance.

## Compatibility

| Item | Support |
| --- | --- |
| Android | 9 (API 28) and later |
| Framework | LSPosed API 101 |
| Verified X versions | `12.16.3-release.0`, `12.23.1-prod.01` |

XBlocker first looks for the OkHttp GraphQL adapter, falling back to Jackson / LoganSquare when needed.

## Installation

1. Download and install the APK from [Releases](https://github.com/bileizhen/XBlocker/releases).
2. Enable **XBlocker** in LSPosed and select **X** (`com.twitter.android`). Additionally select **System UI** (`com.android.systemui`) only when using focus notification conversion or the Xiaomi focus-unlock hook. The native Super Island / Fluid Cloud channel does not depend on focus notifications.
3. Force-stop X, then reopen it.
4. Open XBlocker and check the module connection and timeline counts under **Diagnostics**.

If filtering works only while XBlocker remains in the background, allow **XBlocker autostart / associated launch** in your system's app settings (the names vary by device). Open XBlocker once, then restart X and test again. Some users on 0.2.7 reported that enabling autostart restored filtering; this does not mean all background issues have the same cause. There is no need to keep the app locked in the recent apps list.

If you use an app-hiding tool such as HMA-OSS, make sure **X can see XBlocker** (`io.github.bileizhen.xblocker`). Check the hiding rules, templates and Xposed module presets applied to X, then allow XBlocker or disable app hiding for X. Open XBlocker first, then force-stop and reopen X. Check this setting first if the module is loaded but shows “Reporting blocked” or `Unknown authority`. Without reports, a zero count in the interface does not prove that filtering is inactive.

Starting with 0.2.4, the LSPosed module package name is `io.github.bileizhen.xblocker` to comply with the module repository's naming rules. Version 0.2.3 and earlier use the old package name. To upgrade, install the new package and enable its scope again in LSPosed. Both packages can coexist temporarily; uninstall the old one after confirming that the new one works.

## Updates

**Check for updates on launch** is enabled by default in Settings, and you can also check manually at any time. When an update is available, select a server under **Download source** in the dialog. After downloading, tap **Request installation**. On Android 8 and later, you must allow XBlocker to install unknown apps before the first installation.

## Privacy

- Rules are matched locally on your device. Tweet text, account credentials, custom rules and the allowlist are not uploaded.
- Network access is used only for public rule lists, GitHub version checks and update downloads you choose.
- Diagnostic ZIP archives do not include custom rules, the allowlist or block history.

## Building from source

Requires JDK 21, Android SDK 37 and Build Tools 36.0.0. Create a local `local.properties` file, then run:

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

On-device tests:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

If Windows reports `Unable to establish loopback connection`, use `scripts/build-local.ps1`. Use your own signing key for production releases.

## License

XBlocker's original code is licensed under MIT. The interface and some components are ported from [SukiSU-Ultra v4.1.3](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/v4.1.3), and the complete application is distributed under GPL-3.0; see [COPYING](COPYING). Sources and licenses for the rule list, Miuix, Xposed APIs and other dependencies are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

XBlocker is not affiliated with X, LSPosed or the rule-list maintainers.

## Views

<div align="center">

![Views](https://count.getloli.com/@bileizhen_XBlocker?name=bileizhen_XBlocker&theme=original-new&padding=7&offset=0&align=center&scale=1&pixelated=1&darkmode=auto)

</div>
