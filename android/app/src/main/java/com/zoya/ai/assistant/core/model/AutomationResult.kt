package com.zoya.ai.assistant.core.model

import org.json.JSONObject

/**
 * A structured result for every native automation command.
 *
 * The bridge always resolves with a JSON object shaped like:
 *   {
 *     "status": "SUCCESS",
 *     "ok": true,
 *     "data": { ... },
 *     "error": { "code": "...", "message": "..." },
 *     "meta": { "durationMs": 123, "attempt": 1, "recovered": false }
 *   }
 */
data class AutomationResult(
    val status: ResultStatus,
    val data: JSONObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
    val attempt: Int = 1,
    val recovered: Boolean = false
) {
    val ok: Boolean get() = status == ResultStatus.SUCCESS

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("status", status.name)
        root.put("ok", ok)
        if (data != null) root.put("data", data)

        if (errorCode != null || errorMessage != null) {
            val err = JSONObject()
            err.put("code", errorCode ?: status.name)
            err.put("message", errorMessage ?: "")
            root.put("error", err)
        }

        val meta = JSONObject()
        meta.put("durationMs", durationMs ?: 0)
        meta.put("attempt", attempt)
        meta.put("recovered", recovered)
        root.put("meta", meta)
        return root
    }

    fun withDuration(ms: Long): AutomationResult = copy(durationMs = ms)

    companion object {
        fun success(data: JSONObject? = null, message: String? = null): AutomationResult {
            val d = data ?: JSONObject()
            if (message != null) d.put("message", message)
            return AutomationResult(ResultStatus.SUCCESS, d)
        }

        fun failure(code: String, message: String, data: JSONObject? = null): AutomationResult =
            AutomationResult(ResultStatus.FAILURE, data, code, message)

        fun permissionDenied(permission: String, message: String? = null): AutomationResult {
            val d = JSONObject().put("permission", permission)
            return AutomationResult(
                ResultStatus.PERMISSION_DENIED,
                d,
                "PERMISSION_DENIED",
                message ?: "Permission '$permission' is required for this action."
            )
        }

        fun timeout(message: String, data: JSONObject? = null): AutomationResult =
            AutomationResult(ResultStatus.TIMEOUT, data, "TIMEOUT", message)

        fun unsupported(message: String): AutomationResult =
            AutomationResult(ResultStatus.UNSUPPORTED, null, "UNSUPPORTED", message)

        fun cancelled(message: String = "Operation cancelled by user or policy."): AutomationResult =
            AutomationResult(ResultStatus.CANCELLED, null, "CANCELLED", message)

        fun blocked(code: String, message: String): AutomationResult =
            AutomationResult(ResultStatus.BLOCKED, null, code, message)
    }
}
