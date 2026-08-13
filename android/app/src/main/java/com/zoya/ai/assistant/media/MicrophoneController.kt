package com.zoya.ai.assistant.media

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.zoya.ai.assistant.accessibility.NotificationHelper
import com.zoya.ai.assistant.core.model.AutomationResult
import org.json.JSONObject
import java.io.File

/**
 * Microphone handling. Records only after explicit permission + user request,
 * exposes status to the app, and stops recording immediately when requested.
 * Never records secretly.
 */
class MicrophoneController(private val context: Context) {

    companion object {
        private const val TAG = "ZoyaMicrophone"

        @Volatile
        var isRecording: Boolean = false
            private set

        @Volatile
        var lastRecordingPath: String? = null
            private set
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun permissionGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun permissionStatus(): AutomationResult {
        val data = JSONObject()
        data.put("granted", permissionGranted())
        data.put("permission", "RECORD_AUDIO")
        data.put("recording", isRecording)
        data.put("permanentlyDenied", !permissionGranted() && !shouldShowRationale())
        return AutomationResult.success(data)
    }

    private fun shouldShowRationale(): Boolean {
        val activity = context as? android.app.Activity ?: return false
        return activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
    }

    /** Starts recording to an app-private file. */
    fun startRecording(fileName: String? = null): AutomationResult {
        if (isRecording) {
            return AutomationResult.failure("ALREADY_RECORDING", "Microphone is already recording.")
        }
        if (!permissionGranted()) {
            return AutomationResult.permissionDenied(
                "RECORD_AUDIO",
                "Microphone permission is required to record audio."
            )
        }
        return try {
            val dir = File(context.filesDir, "media/recordings").apply { mkdirs() }
            val name = fileName?.takeIf { it.isNotBlank() } ?: "zoya_${System.currentTimeMillis()}.m4a"
            outputFile = File(dir, name)

            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(outputFile!!.absolutePath)
            r.prepare()
            r.start()

            recorder = r
            isRecording = true
            lastRecordingPath = outputFile!!.absolutePath
            NotificationHelper.ensureChannels(context)
            NotificationHelper(context).build(
                NotificationHelper.CHANNEL_MICROPHONE,
                android.R.drawable.presence_audio_online,
                "Zoya is Recording Audio",
                "Microphone is active. Stop recording anytime from Zoya.",
                ongoing = true
            ).let { notif ->
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NotificationHelper.NOTIF_MICROPHONE, notif)
            }

            AutomationResult.success(
                JSONObject()
                    .put("path", outputFile!!.absolutePath)
                    .put("recording", true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Start recording failed", e)
            AutomationResult.failure("RECORD_FAILED", "Could not start recording: ${e.message}")
        }
    }

    /** Stops recording immediately. Safe to call at any time. */
    fun stopRecording(): AutomationResult {
        if (!isRecording) {
            return AutomationResult.failure("NOT_RECORDING", "Microphone is not recording.")
        }
        return try {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NotificationHelper.NOTIF_MICROPHONE)
            AutomationResult.success(
                JSONObject()
                    .put("path", lastRecordingPath ?: JSONObject.NULL)
                    .put("recording", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error", e)
            AutomationResult.failure("STOP_FAILED", "Could not stop recording: ${e.message}")
        }
    }

    fun status(): AutomationResult {
        return AutomationResult.success(
            JSONObject()
                .put("recording", isRecording)
                .put("path", lastRecordingPath ?: JSONObject.NULL)
                .put("permissionGranted", permissionGranted())
        )
    }

    fun release() {
        if (isRecording) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
        }
    }
}
