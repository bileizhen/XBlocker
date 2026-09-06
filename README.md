# XBlocker

使用 **Miuix + Jetpack Compose** 的 LSPosed 模块，在 Android 原生 X 客户端中过滤垃圾评论。界面采用类似 LSPosed 的大标题、状态卡片、分组设置与底部导航。

## 功能

- 从 [x-comment-blocker](https://github.com/amahteru/x-comment-blocker) 同步分类云端词库；内置离线快照，支持手动同步和每 6 小时后台更新。GitHub API + ETag 条件更新，失败后尝试同仓库 raw 地址；失败、空文件、HTML、超大文件均不会覆盖本地词库。
- 默认只过滤回复，允许扩展到首页、搜索等已适配时间线；昵称、用户名检查可单独关闭。按 `promotedMetadata` / `tweetPromotedMetadata` / `promoted_content` 移除推广条目，独立于回复范围及白名单。
- 自定义关键词、`/正则/i`、分类开关、@用户名白名单、文本导入导出、规则测试。正则采用 Java Pattern：支持 `i/m/s/u` 标志，忽略 `g/y`；不支持的语法列入“未启用的规则”，不悄悄降级为字面词。
- 配置传入 X 双通道：优先使用带调用 UID 检查的 ContentProvider（约 5 秒刷新）；提供者不可见时（如 APK 刚更新、URI 授权被系统清除）自动回退到 LSPosed 共享的 `XSharedPreferences`，过滤不依赖模块界面是否打开过。
- 模块激活标记：X 进程内的 Hook 在启动完成、初始化失败或桥接不可用时，经独立于 ContentProvider 的显式广播回报“模块仍在运行”及原因（接收端校验调用 UID）。首页与“运行诊断”据此区分“未被 LSPosed 加载”与“已加载但回报通道受阻”，未连接时直接显示排障步骤。
- “发送日志”可生成诊断 ZIP：含版本、设备、已安装 X 版本、模块激活标记、功能开关、模块统计及当前应用进程日志；不含自定义词库、白名单或拦截记录，无需 root 或存储权限。
- 流体云实时拦截状态（ColorOS 16+）：仅当 X 位于前台时在状态栏显示胶囊，离开约 1–2 秒自动消失。提升为实时活动需要完整组合（`POST_PROMOTED_NOTIFICATIONS` + `android.requestPromotedOngoing=true` + `setShortCriticalText` + ProgressStyle 分段进度 + HIGH 渠道 + FGS + ongoing），前台状态由 Activity 生命周期回报（400ms 防抖），胶囊更新由 Provider 回报事件驱动。首次开启会依次请求通知权限和电池优化白名单。
- 本地拦截计数、最近 200 条记录与真实进程诊断；不保存推文正文。累计计数按最近 200 条记录的条目 ID 去重。
- 适配 X 12.19.x 实际下发的 URT 结构：推文位于 `itemContent.content.tweetResult.result`，正文在 `legacy.full_text` 或扁平字段，作者经 `core.user_result`；兼容 Web 版 `tweet_results.result` 嵌套与详情页 `conversationComponents` 线程结构。回复判定合并 `in_reply_to_status_id_str` 与 `conversation_id_str`。
- 界面移植自 **SukiSU Ultra v4.1.3**（首页状态/计数卡、折叠大标题、悬浮底栏的真实背景采样与折射、关于页渐变、设置分组、保存/分享弹窗）；主题设置支持跟随系统/浅色/深色、Monet（12+）、模糊与液态玻璃（13+）、预测性返回（14+）及 80%–110% 全局缩放。来源与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 兼容性

- Android 最低 9（API 28）。使用传统 Xposed API，框架必须保留 legacy 模块支持。
- 当前适配目标：**X 12.19.1-release.0 / Android 16 / LSPosed 2.2.0 (7854)**，其他版本需观察诊断计数，不能仅以模块启用作为兼容证明。
- **X 12.23.1 起客户端整体重构**（Jackson 与 OkHttp 移除、时间线改走二进制协议），文本级数据入口不复存在，暂不支持；模块在未适配版本上安全降级，诊断页显示“未找到兼容的数据入口”。分析过程与后续方案见 `.research/x1223-analysis.md`。
- LSPosed 2.2.0 模块页会因使用 New XSharedPreferences 显示“已废弃功能”警告：判定为 legacy + `xposedsharedprefs` + others 可读 xml 三者同时命中，属预告性质，当前功能正常。**不要**按提示改成 `xposedminversion=82` 并删除声明——那会使 `MODE_WORLD_READABLE` 直接抛异常并失去回退通道。共享路径的可读位由框架 daemon 强制设为 744，与调用侧 mode 无关，因此本模块使用 `MODE_PRIVATE`；2.3.0 移除 nsp 后 provider 主通道不受影响。

## 安装和启用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`。
2. 在 LSPosed 模块列表中打开 **XBlocker**，启用并仅勾选 **X / com.twitter.android**。作用域变更只对新启动的 X 进程生效。
3. 强行停止并重新打开 X，无需清除 X 数据。
4. 打开 XBlocker：首页应显示“过滤工作中”。浏览/刷新 X 后，“运行诊断”中的“识别时间线”计数应增加。
5. 长期显示“等待 X 连接”时，查看“运行诊断”的激活标记：有标记说明模块已加载、回报通道受阻（可能是 APK 更新后的授权窗口，稍候自愈）；无标记说明模块未被框架加载，回到第 2、3 步。在“规则测试”确认自定义词行为，再查看真实拦截记录。

排障备注：连续快速更新模块 APK 后 LSPosed 偶发不加载新 dex，重装同一 APK（触发 `MY_PACKAGE_REPLACED`）即可恢复；重装同时会清除 provider URI 授权，由 `GrantReceiver` 自动补发。

## 范围与限制

- 仅处理 Jackson 入口返回的已识别 GraphQL 时间线 `entries` / `moduleItems`。不处理浏览器网页版、私信、未知接口或已落盘的旧缓存（更新模块后建议下拉刷新或重启 X）。
- 部分 OEM（如 ColorOS）会在 APK 更新后短暂保留提供者不可见状态；此期间自动走 `XSharedPreferences` 回退，功能不受影响，仅界面统计暂停刷新。
- 保留 cursor、非推文条目、未知字段。对 conversation module 只删除命中的子条目，空模块才整体删除；不因引用推文命中而删除外层推文。
- 数据格式不兼容、解析失败、输入超过 8 MiB 时透传原始数据；读取输入流失败时回放已消费前缀。
- 正则有每条推文共享的字符访问预算与时间检查，达到限制时跳过耗时匹配；超长正文最多检查 32,768 字符。
- 云端“仇恨用语”分类默认关闭，可自行打开；词库会有误判，建议通过分类开关和白名单调整。
- 不会自动拉黑账号、修改账号设置或发送任何内容。所有规则在本地匹配，网络只用于获取公开词库。

## 构建

依赖：JDK 21、Android SDK 37、Build Tools 36.0.0。复制本机 SDK 路径到 `local.properties`（不提交版本库）。

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

- Windows 上若出现 Java `Unable to establish loopback connection`，使用 `scripts/build-local.ps1`：仅为当前构建设置普通长路径的 Java Unix socket 临时目录并在结束后恢复。
- 部分 Windows JDK/Gradle 组合的测试进程无法读取含中文的 classpath；可使用指向本工程的 ASCII 目录 junction 构建（当前机器别名 `D:\Android\xblocker-workspace`），无需复制工程。
- 真机 Android org.json / 词库验证：`.\gradlew.bat :app:connectedDebugAndroidTest`。
- Debug APK 使用本机 debug key；正式发布请配置自己的签名密钥，勿提交仓库。

## 结构

```text
core/                   Kotlin 过滤核心、配置编解码、输入流回放、JVM 测试
app/.../hook/           LSPosed 入口、Jackson 结构识别、配置轮询、诊断与激活标记
app/.../data/           UID 校验桥接、标记接收、本地设置、云同步、WorkManager
app/.../fluid/          流体云胶囊状态与前台服务
app/.../ui/             Miuix 首页、规则、记录、设置、主题与诊断页面
app/src/androidTest/    真机 Android 运行时测试
.research/              逆向研究工件与 X 版本分析笔记（不参与构建）
```

原有代码保留 MIT 许可；引入 SukiSU UI 后，组合应用按 GPL-3.0 分发，见 [COPYING](COPYING)。词库和 UI 依赖来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
