package io.github.xblocker.fluid

import org.json.JSONObject

/** OS2/OS3 shared card template 6 and OS3 summary template 2 (Xiaomi, 2026-01-29). */
internal object XiaomiFocusPayload {
    const val PARAM_KEY = "miui.focus.param"
    const val PICS_KEY = "miui.focus.pics"
    const val ICON_KEY = "miui.focus.pic_xblocker"

    fun create(protocolVersion: Int, blocked: Long, tweets: Long): JSONObject? {
        if (protocolVersion < 2) return null
        val removed = blocked.coerceAtLeast(0)
        val total = maxOf(tweets, removed)
        val percent = if (total == 0L) 0 else (removed.toDouble() / total * 100).toInt().coerceIn(0, 100)
        val params = JSONObject()
            .put("protocol", 1)
            .put("business", "xblocker_filter")
            .put("updatable", true)
            .put("reopen", "reopen")
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("filterWhenNoPermission", false)
            .put("ticker", "已拦 ${compactCount(removed)}")
            .put("aodTitle", "XBlocker · 已拦截 $removed 条")
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", "已拦截 $removed / $total 条")
                .put("content", "XBlocker · 本轮拦截占比 $percent%"))
            // Type 1 uses the launcher icon and works on light and dark cards.
            .put("picInfo", JSONObject().put("type", 1))
            .put("progressInfo", JSONObject()
                .put("progress", percent)
                .put("colorProgress", "#E5484D")
                .put("colorProgressEnd", "#E5484D"))
        if (protocolVersion >= 3) {
            params.put("param_island", JSONObject()
                .put("islandProperty", 1)
                .put("islandOrder", false)
                .put("bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", JSONObject()
                        .put("type", 1)
                        .put("picInfo", icon())
                        .put("textInfo", JSONObject().put("title", "已拦")))
                    .put("textInfo", JSONObject()
                        .put("title", compactCount(removed))
                        .put("narrowFont", true)))
                .put("smallIslandArea", JSONObject().put("picInfo", icon())))
        }
        return JSONObject().put("param_v2", params)
    }

    private fun icon() = JSONObject().put("type", 1).put("pic", ICON_KEY)

    // A summary region is only about four Chinese characters wide. Keep full values on the card.
    internal fun compactCount(count: Long): String = when {
        count < 1_000 -> count.coerceAtLeast(0).toString()
        count < 10_000 -> "${count / 1_000}.${count % 1_000 / 100}k"
        count < 1_000_000 -> "${count / 10_000}万"
        else -> "99万+"
    }
}
