package com.zoya.ai.assistant.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

/**
 * AlarmManager receiver that fires a scheduled task. The workflow executes
 * through the WorkManager worker to keep the process lightweight.
 */
class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("taskId") ?: return
        val store = TaskStore(context)
        val task = store.getTask(taskId) ?: return
        if (!task.enabled) return

        val request = OneTimeWorkRequest.Builder(TaskWorker::class.java)
            .setInputData(Data.Builder().putString("taskId", taskId).build())
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
