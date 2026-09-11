<div align="center">

# [XBlocker](https://github.com/bileizhen/XBlocker)

Android 原生 X 客户端的 LSPosed 过滤模块

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

<p><strong>简体中文</strong> | <a href="README.en.md">English</a></p>

</div>

## 项目简介

XBlocker 是一个面向 Android 原生 X 客户端的 LSPosed 过滤模块。它在设备本地匹配规则，隐藏垃圾回复、推广内容和指定分类，整个过程都发生在 X 进程内，不需要把内容发到任何服务器。

模块优先检测 OkHttp GraphQL 入口，必要时回退 Jackson / LoganSquare；过滤入口按宿主实际接口选择，不依赖固定的 X 版本号，未识别的数据结构原样透传。

代码托管于 [GitHub](https://github.com/bileizhen/XBlocker)，同时发布到 [LSPosed 模块仓库](https://github.com/Xposed-Modules-Repo/io.github.bileizhen.xblocker)。

> [!IMPORTANT]
> XBlocker 与 X、LSPosed 及词库维护者没有隶属关系。过滤效果依赖 X 客户端的内部接口，部分适配可能随 X 版本更新而变化。

## 功能特性

### 过滤规则

- 默认过滤回复，也可开启首页和搜索时间线
- 昵称、用户名、推广内容和规则分类可分别开关
- 支持关键词、`/正则/flags` 和 @用户名白名单
- 规则支持导入导出，并可在应用内直接测试
- 云端词库同步 [x-comment-blocker](https://github.com/amahteru/x-comment-blocker)，内置离线快照，支持手动同步和定时更新

### 多版本适配

- 按宿主接口自动选择过滤入口，不依赖单一版本号
- 优先 OkHttp GraphQL，必要时回退 Jackson / LoganSquare
- 未识别的数据结构原样透传，避免误伤时间线

### 实时拦截状态

- 原生通道使用 Android 16 实时通知接口，由系统决定展示为小米超级岛或 O 系流体云
- 原生通道不写入焦点通知参数，焦点通知转换另有独立开关
- 实时状态由 X 进程直接回报和发布，系统自动超时清理，无需常驻后台监控

### 诊断与更新

- 查看模块连接、数据入口、拦截计数和最近 200 条记录
- 可导出诊断 ZIP，便于反馈问题
- 启动时自动检查 GitHub 正式版，也可随时手动检查
- 下载时可选 GitHub 原站或 `gh.dpik.top` 镜像，完成后请求系统安装

### 界面与语言

- Miuix 界面，支持浅色、深色、Monet、模糊、液态玻璃、预测性返回和全局缩放
- 支持简体中文、繁体中文、英文和跟随系统，可在设置中切换；Android 13 及以上也支持系统应用语言设置

## 兼容性

| 项目 | 支持情况 |
| --- | --- |
| Android | 9（API 28）及以上 |
| 框架 | LSPosed API 101 |
| 已验证 X | `12.16.3-release.0`、`12.23.1-prod.01` |

## 安装

1. 从 [Releases](https://github.com/bileizhen/XBlocker/releases) 下载并安装 APK。
2. 在 LSPosed 中启用 **XBlocker**，勾选 **X**（`com.twitter.android`）。只有启用“焦点通知转换”或小米焦点解锁 Hook 时才需要额外勾选 **系统界面**（`com.android.systemui`）；原生超级岛 / 流体云通道不依赖焦点通知。
3. 强行停止 X，再重新打开。
4. 打开 XBlocker，在“运行诊断”确认模块连接和时间线计数。

首次进入时 XBlocker 会弹出一次性引导，按厂商跳转到最接近的系统页面，用于开启自启动和实时活动。

> [!NOTE]
> 从 0.2.4 起，LSPosed 模块包名为 `io.github.bileizhen.xblocker`，以符合模块仓库的包名规则。0.2.3 及更早版本使用旧包名：升级时先安装新包并在 LSPosed 中重新启用作用域，确认新包工作后再卸载旧包，两个包可以暂时并存。

## 常见问题

### 只有保留 XBlocker 后台才能拦截怎么办？

请在系统应用管理中允许 **XBlocker 自启动 / 关联启动**（名称因系统而异），再打开一次 XBlocker，并强行停止后重新打开 X 复测。已有 0.2.7 用户反馈开启模块自启动后恢复正常；这不代表所有后台问题都由同一原因引起，也无需因此长期锁定后台卡片。

### 模块已加载，却显示“回报受阻”或 `Unknown authority`？

使用 HMA-OSS 等应用隐藏工具时，请确保 **X 能看见 XBlocker**（`io.github.bileizhen.xblocker`）：检查针对 X 的隐藏规则、模板及 Xposed 模块预设，放行 XBlocker，或关闭针对 X 的应用隐藏。修改后先打开 XBlocker，再强行停止并重新打开 X。未收到回报时，界面的零计数不能证明过滤未运行，请结合回复是否被隐藏判断。

### 如何更新到新版本？

设置中的“启动时自动检查更新”默认开启，也可以随时手动检查。发现新版本后，在弹窗中点击“下载源”选择服务器；下载完成点击“请求安装”。Android 8 及以上首次安装时，需要允许 XBlocker 安装未知应用。

### 界面语言怎么切换？

在“设置 → 语言”中选择简体中文、繁体中文、英文或跟随系统；Android 13 及以上也可以在系统设置的“应用语言”中单独指定。未支持的语言回退到英文，切换语言不影响规则、白名单和已有配置。翻译维护见 [I18N.md](I18N.md)。

### 安装后需要做哪些一次性设置？

XBlocker 会在首次进入时引导开启自启动和实时活动。自启动保证云端词库能在后台定时同步；实时活动决定拦截胶囊能否显示为小米超级岛或 O 系流体云。引导每个安装只提示一次，选择“暂不”后不再打扰。

## 隐私

- 规则匹配在设备本地完成，不上传推文正文、账号凭据、自定义规则或白名单。
- 网络只用于公开词库、GitHub 版本检查和用户主动选择的更新下载。
- 诊断 ZIP 不包含自定义词库、白名单或拦截记录。

## 从源码构建

需要 JDK 21、Android SDK 37 和 Build Tools 36.0.0。创建本地 `local.properties` 后执行：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

真机测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

> [!NOTE]
> Windows 遇到 `Unable to establish loopback connection` 时，改用 `scripts/build-local.ps1`。正式发布请使用自己的签名密钥。

## 参与开发

- [多语言维护说明](I18N.md)
- [第三方依赖与许可证](THIRD_PARTY_NOTICES.md)
- [GPL-3.0 完整协议](COPYING)

## 开源协议

XBlocker 原有代码采用 MIT；界面和部分组件移植自 [SukiSU-Ultra v4.1.3](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/v4.1.3)，完整应用按 GPL-3.0 分发，详见 [COPYING](COPYING)。词库、Miuix、Xposed API 及其他依赖的来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)：模块运行框架
- [x-comment-blocker](https://github.com/amahteru/x-comment-blocker)：云端词库来源
- [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra)：界面与部分组件来源
- [Miuix](https://github.com/compose-miuix-ui/miuix)：界面组件库
- [libxposed/service](https://github.com/libxposed/service)：模块服务 binder 协议参考

## 浏览量

<div align="center">

![:shell](https://count.getloli.com/@bileizhen_XBlocker?name=bileizhen_XBlocker&theme=original-new&padding=7&offset=0&align=center&scale=1&pixelated=1&darkmode=auto)

</div>
