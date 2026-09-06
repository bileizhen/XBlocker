package io.github.xblocker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

private val licenses = listOf(
    "第三方来源与修改说明" to "THIRD_PARTY_NOTICES.md",
    "SukiSU · GNU GPL v3" to "licenses/SukiSU-GPL-3.0.txt",
    "Miuix / AndroidX · Apache 2.0" to "licenses/Apache-2.0.txt",
    "XBlocker 原有代码 · MIT" to "licenses/XBlocker-MIT.txt",
    "云端词库 · MIT" to "UPSTREAM-LICENSE.txt",
)

@Composable
internal fun AboutDocumentScreen(privacy: Boolean, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val document by produceState("", selected) {
        value = if (selected.isEmpty()) "" else withContext(Dispatchers.IO) {
            runCatching { context.assets.open(selected).bufferedReader().use { it.readText() } }
                .getOrElse { "无法读取许可文件" }
        }
    }
    Scaffold(topBar = {
        SmallTopAppBar(title = if (privacy) "隐私说明" else "开源许可", navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回关于") }
        })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (privacy) {
                item { Card { BasicComponent(title = "在本地过滤", summary = "推文、昵称和用户名仅在设备上匹配规则，不上传推文、账号凭据、自定义规则或白名单。") } }
                item { Card { BasicComponent(title = "联网用途", summary = "仅从公开的 GitHub 词库项目下载规则和检查更新，不发送推文内容。") } }
                item { Card { BasicComponent(title = "本地记录", summary = "保存拦截计数及最近 200 条拦截记录，不保存推文正文。可在“记录”页清空。") } }
                item { Card { BasicComponent(title = "日志导出", summary = "仅在你选择保存或发送日志时生成诊断包，包含设备与应用版本、功能状态、模块统计和本应用进程日志。分享对象由你在系统分享面板中选择。诊断包不包含自定义词库、白名单或拦截记录。") } }
                item { Card { BasicComponent(title = "账号操作", summary = "只隐藏命中的内容，不执行拉黑、发帖或其他账号操作。") } }
            } else {
                item { Card { BasicComponent(title = "XBlocker", summary = "引入 SukiSU 界面代码的组合应用按 GNU GPL v3 分发。原有代码、词库与各依赖保留各自许可及版权声明。") } }
                item { Card { licenses.forEach { (title, asset) -> ArrowPreference(title = title, onClick = { selected = asset }) } } }
            }
        }
        OverlayDialog(show = selected.isNotEmpty(), title = licenses.firstOrNull { it.second == selected }?.first ?: "开源许可", onDismissRequest = { selected = "" }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(document.ifEmpty { "正在读取…" }, fontSize = 14.sp)
            }
            TextButton("关闭", onClick = { selected = "" }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}
