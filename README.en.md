<div align="center">

# [XBlocker](https://github.com/bileizhen/XBlocker)

An LSPosed filtering module for the native X client on Android

<p>
  <a href="https://github.com/bileizhen/XBlocker/stargazers"><img src="https://img.shields.io/github/stars/bileizhen/XBlocker" alt="GitHub Stars"></a>
  <a href="https://github.com/bileizhen/XBlocker/issues"><img src="https://img.shields.io/github/issues/bileizhen/XBlocker" alt="GitHub Issues"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="MIT License"></a>
  <a href="https://developer.android.com/about/versions/pie"><img src="https://img.shields.io/badge/Android-9%2B-blue.svg" alt="Android 9+"></a>
  <a href="https://lsposed.org"><img src="https://img.shields.io/badge/LSPosed-API_101-orange.svg" alt="LSPosed API 101"></a>
  <a href="https://github.com/bileizhen/XBlocker/releases"><img src="https://img.shields.io/github/v/tag/bileizhen/XBlocker?label=release" alt="Latest Release"></a>
  <a href="https://github.com/bileizhen/XBlocker/releases"><img src="https://img.shields.io/github/downloads/bileizhen/XBlocker/total" alt="Downloads"></a>
</p>

<img src=".github/img/icon-512.png" width="96" alt="XBlocker">

<p><a href="README.md">简体中文</a> | <strong>English</strong></p>

</div>

## Introduction

XBlocker is an LSPosed filtering module for the native X client on Android. It matches rules locally on your device to hide spam replies, promoted content and selected categories, all inside the X process without sending content to any server.

The module looks for the OkHttp GraphQL entry point first and falls back to Jackson / LoganSquare when needed. The filtering adapter is chosen from the host's actual interfaces rather than a fixed X version, and unrecognized structures pass through unchanged.

The code is hosted on [GitHub](https://github.com/bileizhen/XBlocker) and also published to the [LSPosed module repository](https://github.com/Xposed-Modules-Repo/io.github.bileizhen.xblocker).

> [!IMPORTANT]
> XBlocker is not affiliated with X, LSPosed or the rule-list maintainers. Filtering depends on X's internal interfaces, so some adapters may need updating as X releases new versions.

## Features

### Filtering rules

- Filters replies by default, with optional filtering for Home and Search timelines
- Display names, usernames, promoted content and rule categories can be toggled independently
- Supports keywords, `/regex/flags` and an @username allowlist
- Rules can be imported, exported and tested in the app
- Cloud rules sync with [x-comment-blocker](https://github.com/amahteru/x-comment-blocker), with a bundled offline snapshot and manual or scheduled updates

### Multiple X versions

- Selects a filtering adapter from the host interfaces rather than a single version number
- Prefers OkHttp GraphQL, falling back to Jackson / LoganSquare
- Unrecognized structures pass through unchanged so the timeline is never damaged

### Live blocking status

- The native channel uses Android 16 live notification APIs; the system decides whether to display Xiaomi Super Island or OPlus Fluid Cloud
- The native channel does not inject focus notification parameters; focus notification conversion has a separate switch
- The X process reports and publishes live status directly, and the system clears it after a timeout, so no persistent background monitor is needed

### Diagnostics and updates

- View the module connection, data adapter, block counts and the latest 200 records
- Export diagnostics as a ZIP archive for bug reports
- Automatically checks GitHub stable releases on launch, with manual checks available at any time
- Download from GitHub or the `gh.dpik.top` mirror, then request installation by the system

### Interface and languages

- Miuix interface with light and dark themes, Monet colors, blur, Liquid Glass, predictive back gestures and global display scaling
- Simplified Chinese, Traditional Chinese, English and a follow-system option, switchable in Settings; Android 13 and later also support the system's per-app language settings

## Compatibility

| Item | Support |
| --- | --- |
| Android | 9 (API 28) and later |
| Framework | LSPosed API 101 |
| Verified X versions | `12.16.3-release.0`, `12.23.1-prod.01` |

## Installation

1. Download and install the APK from [Releases](https://github.com/bileizhen/XBlocker/releases).
2. Enable **XBlocker** in LSPosed and select **X** (`com.twitter.android`). Additionally select **System UI** (`com.android.systemui`) only when using focus notification conversion or the Xiaomi focus-unlock hook; the native Super Island / Fluid Cloud channel does not depend on focus notifications.
3. Force-stop X, then reopen it.
4. Open XBlocker and check the module connection and timeline counts under **Diagnostics**.

On first launch XBlocker shows a one-time guide that deep-links to the closest OEM settings page for enabling autostart and live activities.

> [!NOTE]
> Starting with 0.2.4 the LSPosed module package name is `io.github.bileizhen.xblocker`, to comply with the module repository's naming rules. Version 0.2.3 and earlier use the old package name: install the new package, enable its scope again in LSPosed, and uninstall the old one after confirming that the new one works. Both packages can coexist temporarily.

## FAQ

### Filtering only works while XBlocker stays in the background?

Allow **XBlocker autostart / associated launch** in your system's app settings (the names vary by device), open XBlocker once, then force-stop and reopen X to test again. Some users on 0.2.7 reported that enabling autostart restored filtering; this does not mean all background issues share the same cause, and there is no need to keep the app locked in the recent apps list.

### The module is loaded but shows "Reporting blocked" or `Unknown authority`

If you use an app-hiding tool such as HMA-OSS, make sure **X can see XBlocker** (`io.github.bileizhen.xblocker`): check the hiding rules, templates and Xposed module presets applied to X, then allow XBlocker or disable app hiding for X. Open XBlocker first, then force-stop and reopen X. Without reports, a zero count in the interface does not prove that filtering is inactive; check whether replies are actually hidden.

### How do I update?

**Check for updates on launch** is enabled by default in Settings, and you can also check manually at any time. When an update is available, select a server under **Download source** in the dialog. After downloading, tap **Request installation**. On Android 8 and later you must allow XBlocker to install unknown apps before the first installation.

### How do I change the interface language?

Choose Simplified Chinese, Traditional Chinese, English or follow-system under Settings → Language; on Android 13 and later you can also set a per-app language in system settings. Unsupported languages fall back to English, and switching languages does not affect rules, the allowlist or existing configuration. See [I18N.md](I18N.md) for translation maintenance.

### Which one-time settings are needed after install?

XBlocker guides you through enabling autostart and live activities on first launch. Autostart keeps the cloud rule list syncing in the background; live activities decide whether the blocking capsule can appear as Xiaomi Super Island or OPlus Fluid Cloud. The guide is shown once per install, and choosing "Not now" stops the prompt.

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

> [!NOTE]
> If Windows reports `Unable to establish loopback connection`, use `scripts/build-local.ps1`. Use your own signing key for production releases.

## Contributing

- [Translation maintenance](I18N.md)
- [Third-party dependencies and licenses](THIRD_PARTY_NOTICES.md)
- [Full GPL-3.0 text](COPYING)

## License

XBlocker's original code is licensed under MIT. The interface and some components are ported from [SukiSU-Ultra v4.1.3](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/v4.1.3), and the complete application is distributed under GPL-3.0; see [COPYING](COPYING). Sources and licenses for the rule list, Miuix, Xposed APIs and other dependencies are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgements

- [LSPosed](https://github.com/LSPosed/LSPosed): the module runtime
- [x-comment-blocker](https://github.com/amahteru/x-comment-blocker): source of the cloud rule list
- [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra): source of the interface and some components
- [Miuix](https://github.com/compose-miuix-ui/miuix): interface component library
- [libxposed/service](https://github.com/libxposed/service): reference for the module service binder protocol

## Views

<div align="center">

![Views](https://count.getloli.com/@bileizhen_XBlocker?name=bileizhen_XBlocker&theme=original-new&padding=7&offset=0&align=center&scale=1&pixelated=1&darkmode=auto)

</div>
