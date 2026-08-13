package com.zoya.ai.assistant.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.ResultStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Task scheduler using WorkManager (one-time / interval) and AlarmManager
 * (daily / weekly exact triggers). Prevents duplicate execution through a
 * per-task running lock. Event-triggered tasks run when explicitly fired.
 */
class TaskScheduler(
    private val context: Context,
    private val executor: (com.zoya.ai.assistant.tasks.Task) -> AutomationResult
) {

    private val store = TaskStore(context)

    fun createTask(args: Map<String, Any?>): AutomationResult {
        val workflow = args["workflow"]?.toString()
            ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow definition is required.")
        val name = args["name"]?.toString() ?: "Task " + System.currentTimeMillis()
        val scheduleType = runCatching {
            Task.ScheduleType.valueOf((args["scheduleType"]?.toString() ?: "ONCE").uppercase())
        }.getOrDefault(Task.ScheduleType.ONCE)
        val trigger = args["trigger"] as? Map<*, *>
        val triggerJson = JSONObject()
        trigger?.forEach { (k, v) -> triggerJson.put(k.toString(), v) }

        // Validate the workflow parses before persisting.
        try {
            com.zoya.ai.assistant.tasks.workflow.WorkflowParser.parse(workflow)
        } catch (e: Exception) {
            return AutomationResult.failure("INVALID_WORKFLOW", "Workflow definition is invalid: ${e.message}")
        }

        val task = Task(
            id = "task_" + System.currentTimeMillis(),
            name = name,
            workflow = workflow,
            scheduleType = scheduleType,
            triggerConfig = triggerJson,
            enabled = args["enabled"] as? Boolean ?: true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val saved = store.saveTask(task)
        if (!saved) return AutomationResult.failure("STORE_FAILED", "Could not persist the task.")

        val scheduled = schedule(task)
        if (!scheduled.ok) return scheduled

        return AutomationResult.success(
            JSONObject().put("task", task.copy(nextRunAt = task.nextRunAt).toJson())
        )
    }

    fun listTasks(): AutomationResult {
        val arr = JSONArray()
        store.allTasks().forEach { arr.put(it.toJson()) }
        return AutomationResult.success(JSONObject().put("tasks", arr).put("count", arr.length()))
    }

    fun updateTask(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "task id required.")
        val existing = store.getTask(id) ?: return AutomationResult.failure("NOT_FOUND", "Task '$id' not found.")
        val updated = existing.copy(
            name = args["name"]?.toString() ?: existing.name,
            workflow = args["workflow"]?.toString() ?: existing.workflow,
            scheduleType = args["scheduleType"]?.toString()?.let {
                runCatching { Task.ScheduleType.valueOf(it.uppercase()) }.getOrDefault(existing.scheduleType)
            } ?: existing.scheduleType,
            triggerConfig = (args["trigger"] as? Map<*, *>)?.let { m ->
                JSONObject().also { j -> m.forEach { (k, v) -> j.put(k.toString(), v) } }
            } ?: existing.triggerConfig,
            enabled = args["enabled"] as? Boolean ?: existing.enabled,
            updatedAt = System.currentTimeMillis()
        )
        if (existing.enabled) cancelScheduled(existing)
        store.saveTask(updated)
        if (updated.enabled) schedule(updated)
        return AutomationResult.success(JSONObject().put("task", updated.toJson()))
    }

    fun deleteTask(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "task id required.")
        val existing = store.getTask(id)
        existing?.let { cancelScheduled(it) }
        if (store.deleteTask(id)) {
            return AutomationResult.success(JSONObject().put("deleted", true).put("id", id))
        }
        return AutomationResult.failure("DELETE_FAILED", "Could not delete task '$id'.")
    }

    fun setEnabled(args: Map<String, Any?>, enabled: Boolean): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "task id required.")
        val existing = store.getTask(id) ?: return AutomationResult.failure("NOT_FOUND", "Task '$id' not found.")
        if (existing.enabled) cancelScheduled(existing)
        val updated = existing.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        store.saveTask(updated)
        if (enabled) schedule(updated)
        return AutomationResult.success(JSONObject().put("task", updated.toJson()))
    }

    fun taskHistory(args: Map<String, Any?>): AutomationResult {
        val taskId = args["taskId"]?.toString()
        val arr = JSONArray()
        val records = if (taskId != null) store.history(taskId) else store.allHistory()
        records.forEach { arr.put(it.toJson()) }
        return AutomationResult.success(JSONObject().put("history", arr).put("count", arr.length()))
    }

    /** Executes a task immediately. */
    fun executeNow(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "task id required.")
        val task = store.getTask(id) ?: return AutomationResult.failure("NOT_FOUND", "Task '$id' not found.")
        if (store.isRunning(task.id)) {
            return AutomationResult.blocked("DUPLICATE_EXECUTION", "Task '$id' is already running.")
        }
        return executeTask(task)
    }

    // Called by the WorkManager worker and alarm receiver.
    fun executeTask(task: Task): AutomationResult {
        if (store.isRunning(task.id)) {
            return AutomationResult.blocked("DUPLICATE_EXECUTION", "Task '${task.id}' is already running.")
        }
        store.setRunning(task.id, true)
        val startedAt = System.currentTimeMillis()
        return try {
            val result = executor(task)
            val record = RunRecord(
                id = "run_" + System.currentTimeMillis(),
                taskId = task.id,
                taskName = task.name,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                success = result.ok,
                status = result.status.name,
                errorMessage = result.errorMessage,
                stepSummary = null
            )
            store.addHistory(record)
            store.saveTask(task.copy(
                lastRunAt = startedAt,
                lastRunStatus = result.status.name,
                runCount = task.runCount + 1
            ))
            result
        } finally {
            store.setRunning(task.id, false)
        }
    }

    fun recordRun(taskId: String, success: Boolean, status: String, errorMessage: String?, durationMs: Long, stepSummary: String?) {
        val task = store.getTask(taskId) ?: return
        store.addHistory(
            RunRecord(
                id = "run_" + System.currentTimeMillis(),
                taskId = taskId,
                taskName = task.name,
                startedAt = System.currentTimeMillis(),
                durationMs = durationMs,
                success = success,
                status = status,
                errorMessage = errorMessage,
                stepSummary = stepSummary
            )
        )
        store.saveTask(task.copy(
            lastRunAt = System.currentTimeMillis(),
            lastRunStatus = status,
            runCount = task.runCount + 1
        ))
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    fun schedule(task: Task): AutomationResult {
        if (!task.enabled) return AutomationResult.success()

        when (task.scheduleType) {
            Task.ScheduleType.ONCE -> {
                val delayMs = task.triggerConfig.optLong("delayMs", 0)
                if (delayMs > 0) {
                    scheduleAlarm(task, delayMs, uniqueId(task.id))
                } else {
                    // Execute immediately in the background worker.
                    scheduleOneTime(task)
                }
            }

            Task.ScheduleType.INTERVAL -> {
                val intervalMin = task.triggerConfig.optLong("intervalMinutes", 15).coerceAtLeast(15)
                schedulePeriodic(task, intervalMin)
            }

            Task.ScheduleType.DAILY -> {
                val hour = task.triggerConfig.optInt("hour", 9)
                val minute = task.triggerConfig.optInt("minute", 0)
                val delayMs = msUntilNext(hour, minute)
                scheduleAlarm(task, delayMs, uniqueId(task.id))
            }

            Task.ScheduleType.WEEKLY -> {
                val day = task.triggerConfig.optInt("dayOfWeek", 1)
                val hour = task.triggerConfig.optInt("hour", 9)
                val minute = task.triggerConfig.optInt("minute", 0)
                val delayMs = msUntilNextWeekly(day, hour, minute)
                scheduleAlarm(task, delayMs, uniqueId(task.id))
            }

            Task.ScheduleType.EVENT -> {
                // Event-triggered: no automatic schedule; fired via executeNow.
                store.saveTask(task.copy(nextRunAt = null))
            }
        }
        return AutomationResult.success(JSONObject().put("taskId", task.id).put("scheduled", true))
    }

    private fun scheduleOneTime(task: Task) {
        val request = OneTimeWorkRequest.Builder(TaskWorker::class.java)
            .setInputData(androidx.work.Data.Builder().putString("taskId", task.id).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(task.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
        store.saveTask(task.copy(nextRunAt = System.currentTimeMillis() + 10_000))
    }

    private fun schedulePeriodic(task: Task, intervalMin: Long) {
        val constraints = Constraints.Builder().build()
        val request = PeriodicWorkRequest.Builder(TaskWorker::class.java, intervalMin, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(androidx.work.Data.Builder().putString("taskId", task.id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueWorkName(task.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        store.saveTask(task.copy(nextRunAt = System.currentTimeMillis() + intervalMin * 60_000))
    }

    private fun scheduleAlarm(task: Task, delayMs: Long, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskAlarmReceiver::class.java)
            .putExtra("taskId", task.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + delayMs
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        store.saveTask(task.copy(nextRunAt = triggerAt))
    }

    fun cancelScheduled(task: Task) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(task.id))
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskAlarmReceiver::class.java).putExtra("taskId", task.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            uniqueId(task.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun uniqueWorkName(taskId: String): String = "zoya_task_$taskId"

    private fun uniqueId(taskId: String): Int = taskId.hashCode()

    private fun msUntilNext(hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        var target = cal.timeInMillis
        if (target <= now) target += 24 * 60 * 60 * 1000L
        return target - now
    }

    private fun msUntilNextWeekly(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.DAY_OF_WEEK, dayOfWeek)
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        var target = cal.timeInMillis
        while (target <= now) target += 7 * 24 * 60 * 60 * 1000L
        return target - now
    }
}
