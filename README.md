# XBlocker

XBlocker 是一个面向 Android 原生 X 客户端的 LSPosed 模块，用本地规则过滤垃圾评论、推广内容和不想看到的分类。它不修改 X 客户端文件，也不会自动拉黑账号或代替用户发言。

| 下载 | 文档 |
| --- | --- |
| [下载最新版本](https://github.com/bileizhen/XBlocker/releases/latest) · [v0.2.2 APK](https://github.com/bileizhen/XBlocker/releases/download/v0.2.2/XBlocker-0.2.2.apk) | [多版本适配说明](docs/multiversion-support.md) · [12.16.3 适配记录](docs/x-12.16.3.md) |

## 功能

- **云端词库**：同步 [amahteru/x-comment-blocker](https://github.com/amahteru/x-comment-blocker) 的分类词库，内置离线快照，支持手动同步和每 6 小时后台更新。
- **按需过滤**：默认只过滤回复，也可以检查首页、搜索等时间线；昵称、用户名、推广内容和词库分类都能单独开关。
- **自定义规则**：支持关键词、`/正则/flags`、@用户名白名单，以及文本导入、导出和规则测试。无法安全转换的正则会列入“未启用的规则”，不会静默当成普通文字。
- **多版本适配**：按宿主实际接口选择过滤入口，而不是写死某个 X 版本号；已验证 X `12.16.3-release.0` 和 `12.23.1-prod.01`。
- **内置更新**：启动时自动检查 GitHub 最新正式版，也可以在设置中手动检查。更新弹窗支持选择 GitHub 原站或 `gh.dpik.top` 镜像，在应用内下载 APK 后请求系统安装。自动检查可以关闭。
- **诊断与记录**：显示模块连接、数据入口、解析次数和拦截计数，保留最近 200 条记录；可导出诊断 ZIP，方便排查 LSPosed 或 X 版本变化。
- **流体云**：ColorOS 16+ 可在 X 位于前台时显示实时拦截状态；首次开启会请求通知权限和电池优化白名单。
- **界面设置**：提供跟随系统、浅色、深色和 Monet 主题，以及模糊、液态玻璃、预测性返回和 80%–110% 全局缩放。

## 兼容性

| 项目 | 支持情况 |
| --- | --- |
| Android | 最低 Android 9（API 28） |
| 框架 | LSPosed，需保留 legacy 模块支持 |
| 已验证 X 版本 | `12.16.3-release.0`、`12.23.1-prod.01` |
| 历史验证 | `12.19.1-release.0` 曾通过旧 Jackson 入口验证，本轮未重新装机测试 |
| 未知版本 | 只在识别到兼容接口和数据结构时过滤，未知响应原样透传 |

X 的不同发布包可能同时包含新旧客户端代码，因此 XBlocker 在加载时检测宿主能力：优先使用 OkHttp GraphQL 响应入口，接口不存在或安装失败时回退 Jackson / LoganSquare。多版本共用同一适配器时不需要逐个添加版本白名单。详细记录见[多版本适配说明](docs/multiversion-support.md)。

## 安装和启用

1. 从 [Releases](https://github.com/bileizhen/XBlocker/releases) 下载 APK 并安装。
2. 在 LSPosed 模块列表中启用 **XBlocker**，作用域只选择 **X**（`com.twitter.android`）。
3. 强行停止 X，再重新打开。作用域或模块变更只对新启动的 X 进程生效。
4. 打开 XBlocker，首页应显示模块连接状态；在“运行诊断”中确认数据入口和识别时间线计数。
5. 在“规则测试”中先验证自定义规则，再回到 X 刷新时间线查看拦截记录。

如果一直显示“等待 X 连接”，先确认作用域和 X 进程已经重启，再查看“运行诊断”的模块激活标记。连续更新 APK 后若 LSPosed 没有加载新代码，重装同一个 APK 并重新启动 X 通常可以恢复。

## 更新应用

- **自动检查**：设置中的“启动时自动检查更新”默认开启。关闭后仍可通过设置底部的“检查更新”手动检查。
- **下载源**：更新弹窗里的“下载源”是单选组件，点击后选择 GitHub 原站或 `gh.dpik.top` 镜像。
- **安装更新**：下载完成后点击“请求安装”。Android 8 及以上如果尚未允许本应用安装未知来源，需要先在系统设置中开启权限，再次点击安装。
- 只接受 GitHub 的最新正式版并要求发布页包含 APK；草稿版、预发布版或缺少 APK 的发布不会被当作更新。

## 隐私和权限

- 规则匹配在设备本地完成。XBlocker 不上传推文正文、账号凭据、自定义规则或白名单。
- 网络仅用于同步公开词库、检查 GitHub 正式版和下载用户主动选择的更新包。
- 诊断 ZIP 只包含应用、设备、X 版本、模块状态、功能开关、统计信息和有限的应用进程日志，不包含自定义词库、白名单或拦截记录。
- 通知权限和电池优化白名单只用于可选的流体云状态；安装更新时的未知来源权限也只在用户主动请求安装时使用。

## 排障提示

- **没有拦截效果**：确认 LSPosed 作用域只包含 X，强行停止并重启 X，然后在“运行诊断”查看数据入口和识别时间线计数。
- **换 X 版本后计数不变**：先刷新时间线或重启 X；已经缓存的旧内容不会被重新改写。
- **规则没有生效**：检查总开关、回复范围、分类开关和白名单，再使用“规则测试”确认命中原因。
- **LSPosed 显示 legacy 警告**：LSPosed 2.2.0 可能因为 `xposedsharedprefs` 显示“已废弃功能”。这是兼容回退通道的提示，不要删除该声明或改成不兼容的最低版本。
- **流体云不显示**：确认通知权限、电池优化白名单和“流体云实时显示拦截”开关都已开启；只有 X 在前台时才显示。

## 从源码构建

环境要求：JDK 21、Android SDK 37、Build Tools 36.0.0。首次构建前在本地创建 `local.properties` 指向 Android SDK，不要提交该文件。

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

真机 Android 测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Windows 上如果 Gradle 报 `Unable to establish loopback connection`，使用 `scripts/build-local.ps1`；脚本只为当前构建设置 Java 的临时 Unix socket 目录，结束后会恢复环境。Debug APK 使用本机 debug key，正式发布前请配置自己的签名密钥。

## 项目结构

```text
core/                   过滤核心、规则解析、配置编解码和 JVM 测试
app/.../hook/           LSPosed 入口、X 响应适配和模块激活标记
app/.../data/           本地设置、云同步、更新下载、诊断和桥接
app/.../fluid/          流体云状态与前台服务
app/.../ui/             Compose/Miuix 首页、规则、记录、设置和关于页
app/src/androidTest/    Android 真机测试
docs/                   X 版本适配和验证记录
```

## 许可和第三方声明

XBlocker 原有代码保留 MIT 许可；界面和部分组件移植自 [SukiSU-Ultra v4.1.3](https://github.com/SukiSU-Ultra/SukiSU-Ultra/tree/v4.1.3)，按 GPL-3.0 分发。因此包含 GPL 代码的完整应用按 GPL-3.0 分发，详见 [COPYING](COPYING)。云端词库、Miuix、Xposed API 和其他依赖的许可证与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

XBlocker 与 X、LSPosed 及词库维护者没有隶属关系。
