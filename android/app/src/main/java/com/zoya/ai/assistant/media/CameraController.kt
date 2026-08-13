package com.zoya.ai.assistant.media

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zoya.ai.assistant.core.model.AutomationResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Camera integration built on CameraX. Supports front/rear cameras, secure
 * saving to app-private storage, permission status detection, and
 * denied / permanently-denied handling. The camera is NEVER activated
 * without an explicit user-triggered request.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "ZoyaCamera"

        @Volatile
        var lastCapturePath: String? = null
            private set

        @Volatile
        var lastCaptureMime: String = "image/jpeg"
            private set

        @Volatile
        var isCameraActive: Boolean = false
            private set
    }

    private val executor = ContextCompat.getMainExecutor(context)

    fun permissionGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun permissionStatus(): AutomationResult {
        val data = JSONObject()
        data.put("granted", permissionGranted())
        data.put("permission", "CAMERA")
        data.put("permanentlyDenied", permanentlyDenied())
        return AutomationResult.success(data)
    }

    fun permanentlyDenied(): Boolean {
        // If not granted and rationales are no longer shown, the user has
        // denied the permission permanently.
        return !permissionGranted() && !shouldShowRationale()
    }

    private fun shouldShowRationale(): Boolean {
        val activity = lifecycleOwner as? android.app.Activity ?: return false
        return activity.shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)
    }

    /**
     * Takes a photo using the requested camera (front/back). The photo is
     * saved to app-private storage (media/captures) so it stays secure.
     */
    fun takePhoto(camera: String, fileName: String? = null): CompletableFuture<AutomationResult> {
        val future = CompletableFuture<AutomationResult>()
        if (!permissionGranted()) {
            future.complete(
                AutomationResult.permissionDenied("CAMERA", "Camera permission is required to take a photo.")
            )
            return future
        }

        val selector = when (camera.lowercase()) {
            "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }

        val dir = File(context.filesDir, "media/captures").apply { mkdirs() }
        val name = fileName?.takeIf { it.isNotBlank() } ?: "zoya_${System.currentTimeMillis()}.jpg"
        val output = File(dir, name)

        ProcessCameraProvider.getInstance(context).addListener({
            val provider = ProcessCameraProvider.getInstance(context).get()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            if (!provider.hasCamera(selector)) {
                future.complete(AutomationResult.failure("CAMERA_UNAVAILABLE", "The requested camera is not available."))
                return@addListener
            }

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
            isCameraActive = true

            val outputOptions = ImageCapture.OutputFileOptions.Builder(output).build()
            imageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        lastCapturePath = output.absolutePath
                        lastCaptureMime = "image/jpeg"
                        provider.unbindAll()
                        isCameraActive = false
                        val data = JSONObject()
                        data.put("path", output.absolutePath)
                        data.put("name", output.name)
                        data.put("size", output.length())
                        data.put("mimeType", lastCaptureMime)
                        data.put("camera", camera)
                        future.complete(AutomationResult.success(data))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        provider.unbindAll()
                        isCameraActive = false
                        Log.e(TAG, "Capture error", exception)
                        future.complete(AutomationResult.failure("CAPTURE_ERROR", "Photo capture failed: ${exception.message}"))
                    }
                }
            )
        }, executor)

        return future
    }

    fun release() {
        if (isCameraActive) {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            isCameraActive = false
        }
    }
}
