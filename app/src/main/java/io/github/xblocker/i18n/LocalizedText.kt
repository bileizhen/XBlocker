package io.github.xblocker.i18n

import android.content.Context
import io.github.xblocker.R

/** Core labels and persisted errors remain stable across language changes. */
object LocalizedText {
    fun resolve(context: Context, value: String): String {
        if (value.startsWith("同步失败，保留本地词库：")) {
            return context.getString(R.string.sync_failed, resolve(context, value.substringAfter("：")))
        }
        if (value.startsWith("数据入口初始化失败：")) {
            return context.getString(R.string.adapter_initialization_failed, value.substringAfter("："))
        }
        if (value.startsWith("过滤已跳过：")) {
            return context.getString(R.string.filtering_skipped, value.substringAfter("："))
        }
        val id = when (value) {
            "未找到兼容的数据入口，需要适配此 X 版本" -> R.string.adapter_not_supported
            "常规屏蔽词" -> R.string.category_general
            "仇恨用语" -> R.string.category_hate
            "用户名" -> R.string.category_username
            "自定义" -> R.string.category_custom
            "正文" -> R.string.field_text
            "昵称" -> R.string.field_name
            "推广" -> R.string.category_promoted
            "推广广告" -> R.string.reason_promoted
            "规则超过 1000 字符" -> R.string.rule_too_long
            "不支持的 JavaScript 正则标志" -> R.string.regex_flags
            "无效正则" -> R.string.invalid_regex
            "词库超过大小限制" -> R.string.rules_too_large
            "词库格式无效" -> R.string.rules_invalid
            "无法获取词库" -> R.string.rules_unavailable
            "更新信息过大" -> R.string.release_too_large
            "APK 文件过大" -> R.string.apk_too_large
            "下载内容为空" -> R.string.download_empty
            "无法保存更新文件" -> R.string.update_save_failed
            "更新文件不存在" -> R.string.update_missing
            "LSPosed 服务未连接" -> R.string.service_disconnected
            "授权未完成" -> R.string.authorization_incomplete_title
            "请求发送失败" -> R.string.request_failed
            else -> return value
        }
        return context.getString(id)
    }
}
