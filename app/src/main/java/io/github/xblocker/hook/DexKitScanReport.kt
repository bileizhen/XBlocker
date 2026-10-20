package io.github.xblocker.hook

import org.json.JSONObject

/** Describes entry-point discovery, not whether LSPosed installed or ran the hook. */
data class DexKitScanReport(
    val hostVersion: String,
    val hostCode: Long,
    val moduleVersion: String,
    val moduleCode: Int,
    val mode: String,
    val success: Int,
    val total: Int = 1,
    val details: String = "",
    val targetDescriptor: String = "",
    val durationMs: Long = 0,
    val hostFingerprint: String = "",
    val previousTargetDescriptor: String = "",
    val cacheChanged: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("hostVersion", hostVersion).put("hostCode", hostCode)
        .put("moduleVersion", moduleVersion).put("moduleCode", moduleCode)
        .put("mode", mode).put("success", success).put("total", total)
        .put("details", details).put("targetDescriptor", targetDescriptor)
        .put("durationMs", durationMs).put("hostFingerprint", hostFingerprint)
        .put("previousTargetDescriptor", previousTargetDescriptor)
        .put("cacheChanged", cacheChanged ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): DexKitScanReport = DexKitScanReport(
            hostVersion = json.optString("hostVersion"), hostCode = json.optLong("hostCode"),
            moduleVersion = json.optString("moduleVersion"), moduleCode = json.optInt("moduleCode"),
            mode = json.optString("mode", "failed"), success = json.optInt("success"),
            total = json.optInt("total", 1), details = json.optString("details"),
            targetDescriptor = json.optString("targetDescriptor"), durationMs = json.optLong("durationMs"),
            hostFingerprint = json.optString("hostFingerprint"),
            previousTargetDescriptor = json.optString("previousTargetDescriptor"),
            cacheChanged = if (json.isNull("cacheChanged")) null else json.optBoolean("cacheChanged"),
        )
    }
}
