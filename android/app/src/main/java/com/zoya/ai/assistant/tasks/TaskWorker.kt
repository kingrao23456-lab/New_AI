package com.zoya.ai.assistant.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zoya.ai.assistant.core.engine.AutomationEngine

/**
 * WorkManager worker that executes a scheduled task's workflow in the
 * background. Prevents duplicate execution via the task running-lock.
 */
class TaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val store = TaskStore(applicationContext)
        val task = store.getTask(taskId) ?: return Result.success()

        if (store.isRunning(taskId)) {
            // Duplicate execution guard.
            return Result.success()
        }

        val engine = runCatching { AutomationEngine.get() }.getOrNull()
            ?: return Result.failure()

        val result = engine.taskScheduler.executeTask(task)
        return if (result.ok) Result.success() else Result.retry()
    }
}
