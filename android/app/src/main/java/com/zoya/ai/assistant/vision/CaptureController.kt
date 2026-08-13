package com.zoya.ai.assistant.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.accessibility.NotificationHelper

/**
 * Coordinates the MediaProjection consent flow with single-frame OCR capture.
 * The system consent dialog is ALWAYS shown before any capture happens.
 */
class CaptureController(
    private val context: Context,
    private val startForResult: (Intent) -> Unit
) {

    companion object {
        const val REQUEST_CAPTURE = 4101
    }

    private var pendingCallback: ((AutomationResult) -> Unit)? = null
    private var isRequestInFlight = false

    /**
     * Requests user authorization, captures ONE frame, OCRs it, then stops
     * capture immediately.
     */
    fun requestCaptureOnce(callback: (AutomationResult) -> Unit) {
        if (isRequestInFlight) {
            callback(AutomationResult.failure("CAPTURE_BUSY", "Another capture request is already in progress."))
            return
        }
        if (ScreenCaptureService.isCapturing()) {
            callback(AutomationResult.failure("CAPTURE_ACTIVE", "Screen capture is already active. Stop it first."))
            return
        }

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        pendingCallback = callback
        isRequestInFlight = true
        startForResult(intent)
    }

    /** Handles the activity result of the consent dialog. */
    fun handleActivityResult(resultCode: Int, data: Intent?) {
        val callback = pendingCallback
        pendingCallback = null
        isRequestInFlight = false
        if (callback == null) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            callback(AutomationResult.permissionDenied("MEDIA_PROJECTION", "Screen capture permission was not granted."))
            return
        }

        val deferred = OcrEngine.requestSingleFrame()
        ScreenCaptureService.start(context, resultCode, data)

        // Poll the deferred on a background thread (the capture service
        // completes it once the OCR frame is processed), then post the
        // result back to the calling thread.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            Thread {
                val result = try {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(20_000) { deferred.await() }
                    } ?: AutomationResult.timeout("Screen capture timed out.")
                } catch (e: Exception) {
                    AutomationResult.failure("CAPTURE_FAILED", "Screen capture failed: ${e.message}")
                }
                handler.post { callback(result) }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    fun cancelPending() {
        pendingCallback?.invoke(AutomationResult.cancelled("Capture request cancelled."))
        pendingCallback = null
        isRequestInFlight = false
    }
}
