package com.zoya.ai.assistant.tasks

import org.json.JSONObject

/**
 * A scheduled task. Holds a workflow definition plus scheduling metadata.
 */
data class Task(
    val id: String,
    val name: String,
    val workflow: String,
    val scheduleType: ScheduleType,
    val triggerConfig: JSONObject,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val lastRunStatus: String? = null,
    val runCount: Int = 0
) {
    enum class ScheduleType {
        ONCE,
        INTERVAL,
        DAILY,
        WEEKLY,
        EVENT
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("workflow", JSONObject(workflow))
        put("scheduleType", scheduleType.name)
        put("trigger", triggerConfig)
        put("enabled", enabled)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        nextRunAt?.let { put("nextRunAt", it) }
        lastRunAt?.let { put("lastRunAt", it) }
        lastRunStatus?.let { put("lastRunStatus", it) }
        put("runCount", runCount)
    }

    companion object {
        fun fromJson(json: JSONObject): Task = Task(
            id = json.optString("id", "task_" + System.currentTimeMillis()),
            name = json.optString("name", "Untitled Task"),
            workflow = json.optJSONObject("workflow")?.toString() ?: "{}",
            scheduleType = runCatching {
                ScheduleType.valueOf(json.optString("scheduleType", "ONCE"))
            }.getOrDefault(ScheduleType.ONCE),
            triggerConfig = json.optJSONObject("trigger") ?: JSONObject(),
            enabled = json.optBoolean("enabled", true),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            nextRunAt = json.optLongOrNull("nextRunAt"),
            lastRunAt = json.optLongOrNull("lastRunAt"),
            lastRunStatus = json.optStringOrNull("lastRunStatus"),
            runCount = json.optInt("runCount", 0)
        )
    }
}

fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}
