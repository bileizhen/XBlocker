<div align="center">
  <img src="docs/icon-512.png" width="96" alt="XBlocker">
  <h1>XBlocker</h1>
  <p>让 X 时间线少一点噪音。</p>

  [![License](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)
  [![Android 9+](https://img.shields.io/badge/Android-9%2B-blue.svg)](https://www.python.org/)
  [![LSPosed](https://img.shields.io/badge/LSPosed-legacy-orange.svg)](https://lsposed.org)
  [![GitHub](https://img.shields.io/badge/作者-bileizhen-blue)](https://github.com/bileizhen)

  <p>
    <a href="https://github.com/bileizhen/XBlocker/releases/latest">下载最新版</a>
    ·
    <a href="docs/multiversion-support.md">多版本适配</a>
    ·
    <a href="docs/x-12.16.3.md">12.16.3 记录</a>
  </p>
</div>

XBlocker 是一个 Android 原生 X 客户端的 LSPosed 过滤模块。它在本地匹配规则，隐藏垃圾回复、推广内容和指定分类。

## 特性

- **精细过滤**：默认过滤回复，也支持首页和搜索时间线；昵称、用户名、推广内容和分类均可独立开关。
- **自定义规则**：关键词、`/正则/flags`、@用户名白名单、导入导出和规则测试。
- **云端词库**：同步 [x-comment-blocker](https://github.com/amahteru/x-comment-blocker)，内置离线快照，支持手动同步和定时更新。
- **多版本适配**：按宿主接口自动选择过滤入口，不依赖单一版本号；未知结构原样透传。
- **应用内更新**：启动自动检查 GitHub 正式版，可手动检查；下载时可选择 GitHub 原站或 `gh.dpik.top` 镜像，然后请求系统安装。
- **诊断记录**：查看模块连接、数据入口、拦截计数和最近 200 条记录，可导出诊断 ZIP。
- **实时拦截状态**：支持小米 HyperOS 3 超级岛、HyperOS 2 焦点通知和流体云；支持通过 HyperIsland 解锁系统限制的接入方式（待真机验证），其他情况保留普通通知。详见[小米超级岛适配](docs/xiaomi-super-island.md)。
- **Miuix 界面**：支持浅色、深色、Monet、模糊、液态玻璃、预测性返回和全局缩放。

## 兼容性

| 项目 | 支持情况 |
| --- | --- |
| Android | 9（API 28）及以上 |
| 框架 | LSPosed，需保留 legacy 模块支持 |
| 已验证 X | `12.16.3-release.0`、`12.23.1-prod.01` |

XBlocker 优先检测 OkHttp GraphQL 入口，必要时回退 Jackson / LoganSquare。更多验证过程见[多版本适配说明](docs/multiversion-support.md)。

## 安装

1. 从 [Releases](https://github.com/bileizhen/XBlocker/releases) 下载并安装 APK。
2. 在 LSPosed 中启用 **XBlocker**，作用域只选择 **X**（`com.twitter.android`）。
3. 强行停止 X，再重新打开。
4. 打开 XBlocker，在“运行诊断”确认模块连接和时间线计数。

从 0.2.4 起，LSPosed 模块包名为 `io.github.bileizhen.xblocker`，以符合模块仓库的包名规则。0.2.3 及更早版本使用旧包名，升级时需要先安装新包并在 LSPosed 中重新启用作用域；两个包可以暂时并存，确认新包工作后再卸载旧包。

## 更新

设置中的“启动时自动检查更新”默认开启，也可以随时手动检查。发现新版本后，在弹窗中点击“下载源”选择服务器；下载完成点击“请求安装”。Android 8 及以上首次安装时，需要允许 XBlocker 安装未知应用。

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

Windows 遇到 `Unable to establish loopback connection` 时，使用 `scripts/build-local.ps1`。正式发布请使用自己的签名密钥。

## 许可

XBlocker 原有代码采用 MIT；界面和部分组件移植自 [SukiSU-Ultra v4.1.3](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/v4.1.3)，完整应用按 GPL-3.0 分发，详见 [COPYING](COPYING)。词库、Miuix、Xposed API 及其他依赖的来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

XBlocker 与 X、LSPosed 及词库维护者没有隶属关系。

## 浏览量

<div align="center">
  
![:shell](https://count.getloli.com/@bileizhen_XBlocker?name=bileizhen_XBlocker&theme=original-new&padding=7&offset=0&align=center&scale=1&pixelated=1&darkmode=auto)

</div>
