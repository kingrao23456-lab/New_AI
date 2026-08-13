package com.zoya.ai.assistant.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records authorized user gestures through a clearly-visible transparent
 * overlay. Recording is explicit, time-bounded and shows an on-screen "REC"
 * indicator plus a status-bar notification. Recorded paths are stored in
 * normalized coordinates so they replay correctly on any screen size.
 */
class GestureRecorder {

    data class RecordedGesture(
        val id: String,
        val name: String,
        val type: String,
        val points: List<List<Double>>,
        val timestamps: List<Long>,
        val durationMs: Long,
        val screenWidth: Int,
        val screenHeight: Int,
        val orientationDegrees: Int,
        val uiContext: JSONObject?,
        val createdAt: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type)
            val pts = JSONArray()
            points.forEach { p ->
                val pair = JSONArray()
                pair.put(p[0]).put(p[1])
                pts.put(pair)
            }
            put("points", pts)
            val ts = JSONArray()
            timestamps.forEach { ts.put(it) }
            put("timestamps", ts)
            put("durationMs", durationMs)
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("orientationDegrees", orientationDegrees)
            uiContext?.let { put("uiContext", it) }
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(json: JSONObject): RecordedGesture {
                val pts = mutableListOf<List<Double>>()
                val rawPts = json.optJSONArray("points") ?: JSONArray()
                for (i in 0 until rawPts.length()) {
                    val pair = rawPts.getJSONArray(i)
                    pts.add(listOf(pair.getDouble(0), pair.getDouble(1)))
                }
                val ts = mutableListOf<Long>()
                val rawTs = json.optJSONArray("timestamps") ?: JSONArray()
                for (i in 0 until rawTs.length()) ts.add(rawTs.getLong(i))
                return RecordedGesture(
                    id = json.optString("id", "gesture_" + System.currentTimeMillis()),
                    name = json.optString("name", "Untitled Gesture"),
                    type = json.optString("type", "path"),
                    points = pts,
                    timestamps = ts,
                    durationMs = json.optLong("durationMs", 0),
                    screenWidth = json.optInt("screenWidth", 0),
                    screenHeight = json.optInt("screenHeight", 0),
                    orientationDegrees = json.optInt("orientationDegrees", 0),
                    uiContext = json.optJSONObject("uiContext"),
                    createdAt = json.optString("createdAt", "")
                )
            }
        }
    }

    private val TAG = "ZoyaGestureRecorder"
    private val overlayHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var recording = false
    private var cancelRequested = false

    private val points = mutableListOf<List<Double>>()
    private val timestamps = mutableListOf<Long>()
    private var startTimeNanos = 0L
    private var recordStartWallMs = 0L
    private var stopAfterMs = 0L
    private var currentPackage: String? = null
    private var screenW = 0
    private var screenH = 0
    private var orientationDeg = 0
    private var onComplete: ((RecordedGesture?) -> Unit)? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    fun startRecording(
        context: Context,
        durationMs: Long,
        packageName: String?,
        width: Int,
        height: Int,
        orientation: Int,
        onComplete: (RecordedGesture?) -> Unit
    ) {
        if (isRecording) {
            onComplete(null)
            return
        }
        recording = true
        isRecording = true
        cancelRequested = false
        currentPackage = packageName
        screenW = width
        screenH = height
        orientationDeg = orientation
        this.onComplete = onComplete
        points.clear()
        timestamps.clear()
        recordStartWallMs = System.currentTimeMillis()
        stopAfterMs = durationMs

        overlayHandler.post {
            showOverlay(context)
            if (stopAfterMs > 0) {
                overlayHandler.postDelayed({ finishRecording() }, stopAfterMs)
            }
        }
    }

    fun stopRecording(): RecordedGesture? {
        cancelRequested = false
        return finishRecording()
    }

    private fun finishRecording(): RecordedGesture? {
        if (!recording) return null
        recording = false
        isRecording = false
        val gesture = buildGesture()
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.post { hideOverlay() }
        onComplete?.invoke(gesture)
        onComplete = null
        return gesture
    }

    private fun buildGesture(): RecordedGesture? {
        if (points.size < 2) return null
        val duration = (System.currentTimeMillis() - recordStartWallMs)
        return RecordedGesture(
            id = "gesture_" + System.currentTimeMillis(),
            name = "Gesture " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()),
            type = "path",
            points = points.toList(),
            timestamps = timestamps.toList(),
            durationMs = duration,
            screenWidth = screenW,
            screenHeight = screenH,
            orientationDegrees = orientationDeg,
            uiContext = JSONObject().apply {
                currentPackage?.let { put("packageName", it) }
                put("recordedAt", recordStartWallMs)
            },
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        )
    }

    private fun showOverlay(context: Context) {
        if (overlayView != null) return
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val text = TextView(context).apply {
            text = "● REC"
            setTextColor(0xFFEF4444.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(10, 4, 10, 4)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val captureView = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!recording) return true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        points.clear()
                        timestamps.clear()
                        startTimeNanos = event.eventTime
                        addPoint(event, 0L)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        addPoint(event, event.eventTime - startTimeNanos)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        addPoint(event, event.eventTime - startTimeNanos)
                        // Keep recording until stopAfterMs if duration set; otherwise stop on finger up.
                        if (stopAfterMs <= 0) {
                            overlayHandler.post { finishRecording() }
                        }
                    }
                }
                return true
            }
        }

        val captureParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(text, params)
            windowManager?.addView(captureView, captureParams)
            statusText = text
            overlayView = captureView
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Overlay failed (SYSTEM_ALERT_WINDOW required)", e)
            recording = false
            isRecording = false
        }
    }

    private fun hideOverlay() {
        statusText?.let { runCatching { windowManager?.removeView(it) } }
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        statusText = null
        overlayView = null
    }

    private fun addPoint(event: MotionEvent, offsetMs: Long) {
        if (screenW <= 0 || screenH <= 0) return
        val nx = (event.x / screenW).toDouble()
        val ny = (event.y / screenH).toDouble()
        // Avoid duplicate consecutive points at the exact same location.
        if (points.isNotEmpty() && points.last()[0] == nx && points.last()[1] == ny && timestamps.last() == offsetMs) {
            timestamps[timestamps.lastIndex] = offsetMs
            return
        }
        points.add(listOf(nx, ny))
        timestamps.add(offsetMs)
    }

    /** Cancels the active recording session (best effort; overlay removed). */
    fun cancel() {
        cancelRequested = true
        overlayHandler.post {
            if (recording) {
                recording = false
                isRecording = false
                hideOverlay()
                onComplete?.invoke(null)
                onComplete = null
            }
        }
    }

    // ---- Storage helpers ----

    fun toJson(gesture: RecordedGesture): String = gesture.toJson().toString()

    fun fromJson(raw: String): RecordedGesture = RecordedGesture.fromJson(JSONObject(raw))

    fun toExportBundle(gestures: List<RecordedGesture>): String {
        val arr = JSONArray()
        gestures.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }
}
