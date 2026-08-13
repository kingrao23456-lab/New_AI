package com.zoya.ai.assistant.tasks

import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.zoya.ai.assistant.accessibility.NotificationHelper

/**
 * Foreground service used to keep background automation (workflows / voice
 * wake) alive in a compliant way. Shows a persistent notification so the
 * user always knows automation is active. Avoids unnecessary persistence:
 * it is only started while a workflow is actually executing.
 */
class AutomationForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.zoya.ai.assistant.action.START_AUTOMATION_FG"
        const val ACTION_STOP = "com.zoya.ai.assistant.action.STOP_AUTOMATION_FG"
        const val ACTION_STOP_NOW = "com.zoya.ai.assistant.action.STOP_AUTOMATION_NOW"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(context, Intent(context, AutomationForegroundService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AutomationForegroundService::class.java).setAction(ACTION_STOP))
        }

        fun requestStopNow(context: Context) {
            context.startService(Intent(context, AutomationForegroundService::class.java).setAction(ACTION_STOP_NOW))
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_NOW -> {
                // Emergency STOP: cancel the running operation and stop the service.
                runCatching { com.zoya.ai.assistant.core.engine.AutomationEngine.get().cancelCurrentOperation() }
                com.zoya.ai.assistant.core.engine.AutomationEngine.get().logStore.log("cancel", null, "Emergency STOP triggered from notification.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                return START_NOT_STICKY
            }
            else -> {
                val stopNowIntent = PendingIntent.getService(
                    this,
                    0,
                    Intent(this, AutomationForegroundService::class.java).setAction(ACTION_STOP_NOW),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val stopAction = androidx.core.app.NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "STOP",
                    stopNowIntent
                ).build()
                val notification = NotificationHelper(this).build(
                    NotificationHelper.CHANNEL_TASKS,
                    android.R.drawable.stat_sys_upload,
                    "Zoya Automation Active",
                    "A Zoya task or workflow is running in the background.",
                    ongoing = true,
                    actions = listOf(stopAction)
                )
                startForeground(
                    NotificationHelper.NOTIF_TASK_RUNNING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                isRunning = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
