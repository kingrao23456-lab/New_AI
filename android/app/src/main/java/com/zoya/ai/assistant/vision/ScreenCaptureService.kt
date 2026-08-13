package com.zoya.ai.assistant.vision

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.zoya.ai.assistant.accessibility.NotificationHelper
import org.json.JSONObject

/**
 * MediaProjection-based screen capture foreground service.
 *
 * Capture is ALWAYS user-authorized (system consent dialog) and shows a
 * persistent "Zoya is capturing your screen" notification so it can never
 * happen silently. Frames are only used for OCR / visual detection /
 * verification / testing.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ZoyaScreenCapture"
        const val EXTRA_RESULT_CODE = "zoya_result_code"
        const val EXTRA_RESULT_DATA = "zoya_result_data"
        const val ACTION_START = "com.zoya.ai.assistant.action.START_CAPTURE"
        const val ACTION_STOP = "com.zoya.ai.assistant.action.STOP_CAPTURE"

        @Volatile
        var captureActive: Boolean = false
            private set

        @Volatile
        private var serviceInstance: ScreenCaptureService? = null

        fun isCapturing(): Boolean = captureActive

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            serviceInstance?.stopSelf()
            // Service stop may be delayed; also reset the flag optimistically.
            captureActive = false
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var densityDpi = 0
    private var width = 0
    private var height = 0

    private val notificationHelper by lazy { NotificationHelper(this) }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode < 0 || data == null) {
                    Log.e(TAG, "Missing capture consent result")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(
                    NotificationHelper.NOTIF_CAPTURE,
                    notificationHelper.build(
                        NotificationHelper.CHANNEL_CAPTURE,
                        android.R.drawable.ic_menu_camera,
                        "Zoya is Capturing Screen",
                        "Screen capture is active. Tap to open Zoya. Stop it anytime from Zoya."
                    ),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        0
                    }
                )

                initializeProjection(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun initializeProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            stopSelf()
            return
        }

        val metrics = resources.displayMetrics
        densityDpi = metrics.densityDpi
        width = metrics.widthPixels
        height = metrics.heightPixels

        captureThread = HandlerThread("zoya-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader -> onFrameAvailable(reader) }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ZoyaCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
        captureActive = true
        Log.i(TAG, "Capture started ${width}x${height}")

        // The capture is only kept alive for the duration of the foreground
        // service. If OCR is requested once and then stopped, the service is
        // stopped immediately by the caller.
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (OcrEngine.captureSingleFrameRequested) {
                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    captureHandler?.post {
                        OcrEngine.processCaptureFrame(bitmap)
                        stopCapture()
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)

        if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (cropped != bitmap) bitmap.recycle()
            return cropped
        }
        return bitmap
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceInstance = null
        super.onDestroy()
    }

    private fun stopCapture() {
        captureActive = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        notificationHelper.cancelServiceNotification()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Log.i(TAG, "Capture stopped")
    }
}
