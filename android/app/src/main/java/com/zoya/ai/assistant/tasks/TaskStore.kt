package com.zoya.ai.assistant.tasks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent task store backed by SharedPreferences. Also records run history
 * for every task so the app can surface history.
 */
class TaskStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("zoya_tasks", Context.MODE_PRIVATE)

    fun allTasks(): List<Task> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Task.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun getTask(id: String): Task? = allTasks().firstOrNull { it.id == id }

    fun saveTask(task: Task): Boolean {
        val tasks = allTasks().toMutableList()
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) tasks[idx] = task else tasks.add(task)
        return persist(tasks)
    }

    fun deleteTask(id: String): Boolean {
        val tasks = allTasks().filterNot { it.id == id }
        return persist(tasks)
    }

    fun clearTasks(): Boolean {
        return prefs.edit().remove(KEY_TASKS).commit()
    }

    fun history(taskId: String): List<RunRecord> {
        val raw = prefs.getString("$KEY_HISTORY_PREFIX$taskId", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { RunRecord.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun allHistory(): List<RunRecord> {
        return allTasks().flatMap { history(it.id) }.sortedByDescending { it.startedAt }
    }

    fun addHistory(record: RunRecord): Boolean {
        val history = history(record.taskId).toMutableList()
        history.add(0, record)
        if (history.size > 50) history.removeAt(history.size - 1)
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        return prefs.edit().putString("$KEY_HISTORY_PREFIX${record.taskId}", arr.toString()).commit()
    }

    /** Marks a task as running to prevent duplicate execution. */
    fun setRunning(taskId: String, running: Boolean) {
        prefs.edit().putBoolean("$KEY_RUNNING_PREFIX$taskId", running).apply()
    }

    fun isRunning(taskId: String): Boolean = prefs.getBoolean("$KEY_RUNNING_PREFIX$taskId", false)

    private fun persist(tasks: List<Task>): Boolean {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        return prefs.edit().putString(KEY_TASKS, arr.toString()).commit()
    }

    companion object {
        private const val KEY_TASKS = "zoya_tasks"
        private const val KEY_HISTORY_PREFIX = "zoya_task_history_"
        private const val KEY_RUNNING_PREFIX = "zoya_task_running_"
    }
}

data class RunRecord(
    val id: String,
    val taskId: String,
    val taskName: String,
    val startedAt: Long,
    val durationMs: Long,
    val success: Boolean,
    val status: String,
    val errorMessage: String? = null,
    val stepSummary: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("taskId", taskId)
        put("taskName", taskName)
        put("startedAt", startedAt)
        put("durationMs", durationMs)
        put("success", success)
        put("status", status)
        errorMessage?.let { put("errorMessage", it) }
        stepSummary?.let { put("stepSummary", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): RunRecord = RunRecord(
            id = json.optString("id", "run_" + System.currentTimeMillis()),
            taskId = json.optString("taskId", ""),
            taskName = json.optString("taskName", ""),
            startedAt = json.optLong("startedAt", 0),
            durationMs = json.optLong("durationMs", 0),
            success = json.optBoolean("success", false),
            status = json.optString("status", "UNKNOWN"),
            errorMessage = json.optStringOrNull("errorMessage"),
            stepSummary = json.optStringOrNull("stepSummary")
        )
    }
}
