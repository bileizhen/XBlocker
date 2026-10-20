package io.github.xblocker.data

import org.json.JSONObject

enum class ActivationPhase { QUEUED, SCANNING, READY, ACTIVE, FAILED }

/** Discovery success and a running host's loaded module are deliberately separate results. */
data class UpdateActivationState(
    val token: String,
    val moduleCode: Int,
    val moduleVersion: String,
    val phase: ActivationPhase = ActivationPhase.QUEUED,
    val scan: JSONObject? = null,
    val previousPid: Int = 0,
    val activePid: Int = 0,
    val dismissed: Boolean = false,
) {
    val running: Boolean get() = phase in setOf(ActivationPhase.QUEUED, ActivationPhase.SCANNING)
    fun acceptsHost(report: JSONObject): Boolean = phase in setOf(ActivationPhase.READY, ActivationPhase.FAILED) &&
        report.optInt("pid") > 0 && report.optInt("pid") != previousPid &&
        report.optInt("moduleCode") == moduleCode && report.optInt("hooks") > 0

    fun toJson(): JSONObject = JSONObject().put("token", token).put("moduleCode", moduleCode)
        .put("moduleVersion", moduleVersion).put("phase", phase.name).put("scan", scan ?: JSONObject.NULL)
        .put("previousPid", previousPid).put("activePid", activePid).put("dismissed", dismissed)

    companion object {
        fun fromJson(value: JSONObject) = UpdateActivationState(value.getString("token"), value.getInt("moduleCode"),
            value.getString("moduleVersion"), ActivationPhase.valueOf(value.getString("phase")), value.optJSONObject("scan"),
            value.optInt("previousPid"), value.optInt("activePid"), value.optBoolean("dismissed"))
    }
}
