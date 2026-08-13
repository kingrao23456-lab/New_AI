package com.zoya.ai.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.zoya.ai.assistant.core.model.AutomationResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Injects gestures using the official AccessibilityService.dispatchGesture API.
 * All coordinates can be supplied as absolute pixels or normalized (0..1)
 * fractions of the current screen size.
 */
class GestureInjector(
    private val service: AccessibilityService,
    private val screenWidthProvider: () -> Int,
    private val screenHeightProvider: () -> Int
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxGestureDurationMs = 60_000L

    data class GestureSpec(
        val type: String,
        val fromX: Double? = null,
        val fromY: Double? = null,
        val toX: Double? = null,
        val toY: Double? = null,
        val x: Double? = null,
        val y: Double? = null,
        val durationMs: Long = 300,
        val delayMs: Long = 0,
        val points: List<List<Double>>? = null,
        val repeatCount: Int = 1,
        val normalized: Boolean = true,
        val scaleFactor: Double? = null
    )

    private fun resolveX(x: Double?): Int {
        if (x == null) return -1
        return if (isNormalized(x)) (x * screenWidthProvider()).toInt() else x.toInt()
    }

    private fun resolveY(y: Double?): Int {
        if (y == null) return -1
        return if (isNormalized(y)) (y * screenHeightProvider()).toInt() else y.toInt()
    }

    private fun isNormalized(v: Double): Boolean = v > 0.0 && v <= 1.0

    /** Dispatches a single gesture and awaits completion (with timeout). */
    fun dispatch(spec: GestureSpec, timeoutMs: Long = 10_000): AutomationResult {
        if (spec.delayMs > 0) {
            try {
                Thread.sleep(spec.delayMs)
            } catch (e: InterruptedException) {
                return AutomationResult.cancelled("Gesture cancelled during delay.")
            }
        }

        if (spec.repeatCount > 1) {
            val results = mutableListOf<AutomationResult>()
            for (i in 0 until spec.repeatCount) {
                val r = dispatchSingle(spec.copy(repeatCount = 1), timeoutMs)
                results.add(r)
                if (!r.ok) return r
                if (i < spec.repeatCount - 1) {
                    try {
                        Thread.sleep(spec.durationMs.coerceAtMost(500))
                    } catch (e: InterruptedException) {
                        return AutomationResult.cancelled("Repeated gesture cancelled.")
                    }
                }
            }
            return results.last()
        }

        return dispatchSingle(spec, timeoutMs)
    }

    private fun dispatchSingle(spec: GestureSpec, timeoutMs: Long): AutomationResult {
        val gesture = buildGesture(spec) ?: return AutomationResult.blocked("INVALID_GESTURE", "Could not build gesture '${spec.type}'.")
        return awaitDispatch(gesture, timeoutMs)
    }

    private fun awaitDispatch(gesture: GestureDescription, timeoutMs: Long): AutomationResult {
        val future = CompletableFuture<AutomationResult>()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                future.complete(AutomationResult.success())
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                future.complete(AutomationResult.cancelled("Gesture was cancelled by the system."))
            }
        }

        val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
        if (!dispatched) {
            return AutomationResult.failure("GESTURE_DISPATCH_FAILED", "AccessibilityService rejected the gesture (is it enabled?).")
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            AutomationResult.timeout("Gesture did not complete within ${timeoutMs}ms.")
        } catch (e: InterruptedException) {
            AutomationResult.cancelled("Gesture dispatch interrupted.")
        } catch (e: Exception) {
            AutomationResult.failure("GESTURE_ERROR", "Unexpected gesture error: ${e.message}")
        }
    }

    private fun buildGesture(spec: GestureSpec): GestureDescription? {
        val builder = GestureDescription.Builder()

        fun addStroke(path: Path, durationMs: Long, startTimeMs: Long) {
            builder.addStroke(GestureDescription.StrokeDescription(path, startTimeMs, durationMs))
        }

        when (spec.type.lowercase()) {
            "tap", "click" -> {
                val cx = resolveX(spec.x)
                val cy = resolveY(spec.y)
                if (cx < 0 || cy < 0) return null
                addStroke(singlePointPath(cx, cy), spec.durationMs.coerceAtLeast(1), 0)
            }

            "doubletap" -> {
                val cx = resolveX(spec.x)
                val cy = resolveY(spec.y)
                if (cx < 0 || cy < 0) return null
                val tapDuration = spec.durationMs.coerceAtLeast(1)
                addStroke(singlePointPath(cx, cy), tapDuration, 0)
                addStroke(singlePointPath(cx, cy), tapDuration, tapDuration + 80)
            }

            "longpress" -> {
                val cx = resolveX(spec.x)
                val cy = resolveY(spec.y)
                if (cx < 0 || cy < 0) return null
                addStroke(singlePointPath(cx, cy), max(spec.durationMs, 600), 0)
            }

            "swipe", "scroll", "fling" -> {
                val fx = resolveX(spec.fromX)
                val fy = resolveY(spec.fromY)
                val tx = resolveX(spec.toX)
                val ty = resolveY(spec.toY)
                if (fx < 0 || fy < 0 || tx < 0 || ty < 0) return null
                val path = Path().apply {
                    moveTo(fx.toFloat(), fy.toFloat())
                    lineTo(tx.toFloat(), ty.toFloat())
                }
                addStroke(path, spec.durationMs.coerceAtLeast(50), 0)
            }

            "drag" -> {
                val fx = resolveX(spec.fromX)
                val fy = resolveY(spec.fromY)
                val tx = resolveX(spec.toX)
                val ty = resolveY(spec.toY)
                if (fx < 0 || fy < 0 || tx < 0 || ty < 0) return null
                val path = Path().apply {
                    moveTo(fx.toFloat(), fy.toFloat())
                    // drag slowly: interpolate so the system treats it as a drag
                    val steps = 8
                    for (i in 1..steps) {
                        lineTo(
                            (fx + (tx - fx) * i / steps).toFloat(),
                            (fy + (ty - fy) * i / steps).toFloat()
                        )
                    }
                }
                addStroke(path, max(spec.durationMs, 800), 0)
            }

            "path", "custom" -> {
                val rawPoints = spec.points ?: return null
                if (rawPoints.size < 2) return null
                val path = Path()
                rawPoints.forEachIndexed { i, p ->
                    val px = resolveX(p.getOrNull(0))
                    val py = resolveY(p.getOrNull(1))
                    if (px >= 0 && py >= 0) {
                        if (i == 0) path.moveTo(px.toFloat(), py.toFloat()) else path.lineTo(px.toFloat(), py.toFloat())
                    }
                }
                addStroke(path, spec.durationMs.coerceAtLeast(100), 0)
            }

            "pinch", "pinchout", "pinchopen", "zoom", "zoomin", "zoomout" -> {
                val cx = resolveX(spec.x)
                val cy = resolveY(spec.y)
                val factor = (spec.scaleFactor ?: 0.5).coerceIn(0.05, 0.9)
                if (cx < 0 || cy < 0) return null
                val spread = factor * minOf(screenWidthProvider(), screenHeightProvider())
                val duration = spec.durationMs.coerceAtLeast(200)

                if (spec.type.lowercase() in setOf("pinchout", "pinchopen", "zoomin", "zoom")) {
                    // fingers start together, move apart
                    val p1 = Path().apply {
                        moveTo((cx - spread / 4).toFloat(), cy.toFloat())
                        lineTo((cx - spread).toFloat(), cy.toFloat())
                    }
                    val p2 = Path().apply {
                        moveTo((cx + spread / 4).toFloat(), cy.toFloat())
                        lineTo((cx + spread).toFloat(), cy.toFloat())
                    }
                    addStroke(p1, duration, 0)
                    addStroke(p2, duration, 0)
                } else {
                    // zoomout: fingers start apart, come together
                    val p1 = Path().apply {
                        moveTo((cx - spread).toFloat(), cy.toFloat())
                        lineTo((cx - spread / 4).toFloat(), cy.toFloat())
                    }
                    val p2 = Path().apply {
                        moveTo((cx + spread).toFloat(), cy.toFloat())
                        lineTo((cx + spread / 4).toFloat(), cy.toFloat())
                    }
                    addStroke(p1, duration, 0)
                    addStroke(p2, duration, 0)
                }
            }

            else -> return null
        }

        return try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun singlePointPath(x: Int, y: Int): Path = Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo((x + 0.1f).toFloat(), (y + 0.1f).toFloat())
    }

    /** Cancels any in-flight dispatch tracked by a future (best effort). */
    fun cancelInFlight() {
        // dispatchGesture has no official cancel API; in-flight gestures are
        // allowed to complete. Engine-level cancellation is handled by the
        // AutomationEngine via checkpoints between steps.
    }
}
