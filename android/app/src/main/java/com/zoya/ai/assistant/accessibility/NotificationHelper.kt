package com.zoya.ai.assistant.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zoya.ai.assistant.MainActivity

/**
 * Builds the notifications used by the accessibility service and the
 * foreground automation service. Channels are created once on first use.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_SERVICE = "zoya_service"
        const val CHANNEL_RECORDING = "zoya_recording"
        const val CHANNEL_CAPTURE = "zoya_capture"
        const val CHANNEL_TASKS = "zoya_tasks"
        const val CHANNEL_CAMERA = "zoya_camera"
        const val CHANNEL_MICROPHONE = "zoya_microphone"

        const val NOTIF_SERVICE_ACTIVE = 1001
        const val NOTIF_RECORDING = 1002
        const val NOTIF_CAPTURE = 1003
        const val NOTIF_TASK_RUNNING = 1004
        const val NOTIF_CAMERA = 1005
        const val NOTIF_MICROPHONE = 1006

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channels = listOf(
                NotificationChannel(CHANNEL_SERVICE, "Zoya Service", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_RECORDING, "Zoya Gesture Recording", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_CAPTURE, "Zoya Screen Capture", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_TASKS, "Zoya Tasks", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_CAMERA, "Zoya Camera", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_MICROPHONE, "Zoya Microphone", NotificationManager.IMPORTANCE_LOW)
            )
            channels.forEach { nm.createNotificationChannel(it) }
        }
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun build(
        channelId: String,
        smallIcon: Int,
        title: String,
        text: String,
        ongoing: Boolean = true,
        contentIntent: PendingIntent? = null,
        actions: List<NotificationCompat.Action> = emptyList()
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setContentIntent(contentIntent ?: openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        actions.forEach { builder.addAction(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun showServiceActiveNotification() {
        ensureChannels(context)
        nm.notify(
            NOTIF_SERVICE_ACTIVE,
            build(
                CHANNEL_SERVICE,
                android.R.drawable.ic_menu_view,
                "Zoya Accessibility Active",
                "Zoya can read your screen and control apps you authorize."
            )
        )
    }

    fun cancelServiceNotification() {
        runCatching { nm.cancel(NOTIF_SERVICE_ACTIVE) }
    }

    fun showRecordingNotification() {
        nm.notify(
            NOTIF_RECORDING,
            build(
                CHANNEL_RECORDING,
                android.R.drawable.ic_media_play,
                "Zoya Recording Gesture",
                "Recording your touch path. Tap stop in Zoya to finish.",
                ongoing = false
            )
        )
    }

    fun cancelRecordingNotification() {
        runCatching { nm.cancel(NOTIF_RECORDING) }
    }
}
