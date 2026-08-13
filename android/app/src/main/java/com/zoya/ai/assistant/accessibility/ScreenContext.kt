package com.zoya.ai.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Mutable snapshot of the current screen state, maintained by the
 * accessibility service. Used for verification, self-recovery and exposing
 * live context to the web layer.
 */
class ScreenContext {
    @Volatile var currentPackage: String? = null
        private set

    @Volatile var currentClassName: String? = null
        private set

    @Volatile var lastAction: String? = null
        private set

    @Volatile var expectedResult: String? = null
        private set

    @Volatile var lastOcrText: String? = null
        private set

    @Volatile var workflowState: String? = null
        private set

    @Volatile var screenWidth: Int = 0
        private set

    @Volatile var screenHeight: Int = 0
        private set

    @Volatile var orientationDegrees: Int = 0
        private set

    private val lock = Any()
    private var rootRef: AccessibilityNodeInfo? = null

    fun updateFromEvent(
        packageName: String?,
        className: String?,
        root: AccessibilityNodeInfo?,
        screenW: Int,
        screenH: Int,
        orientation: Int
    ) {
        synchronized(lock) {
            currentPackage = packageName
            currentClassName = className
            rootRef?.recycle()
            rootRef = root?.let { AccessibilityNodeInfo.obtain(it) }
            screenWidth = screenW
            screenHeight = screenH
            orientationDegrees = orientation
        }
    }

    fun setLastAction(action: String?) {
        synchronized(lock) { lastAction = action }
    }

    fun setExpectedResult(expected: String?) {
        synchronized(lock) { expectedResult = expected }
    }

    fun setOcrText(text: String?) {
        synchronized(lock) { lastOcrText = text }
    }

    fun setWorkflowState(state: String?) {
        synchronized(lock) { workflowState = state }
    }

    fun getRoot(): AccessibilityNodeInfo? {
        synchronized(lock) {
            return rootRef?.let { AccessibilityNodeInfo.obtain(it) }
        }
    }

    fun recycleRoot() {
        synchronized(lock) {
            rootRef?.recycle()
            rootRef = null
        }
    }

    /**
     * Returns the visible bounds of the current root (whole window) or null.
     */
    fun getWindowBounds(): Rect? {
        val root = getRoot() ?: return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        root.recycle()
        return if (rect.isEmpty) null else rect
    }

    /** Builds a JSON snapshot of current screen state for the web layer. */
    fun toJson(): org.json.JSONObject {
        val json = org.json.JSONObject()
        json.put("packageName", currentPackage ?: org.json.JSONObject.NULL)
        json.put("className", currentClassName ?: org.json.JSONObject.NULL)
        json.put("lastAction", lastAction ?: org.json.JSONObject.NULL)
        json.put("expectedResult", expectedResult ?: org.json.JSONObject.NULL)
        json.put("lastOcrText", lastOcrText ?: org.json.JSONObject.NULL)
        json.put("workflowState", workflowState ?: org.json.JSONObject.NULL)
        json.put("screenWidth", screenWidth)
        json.put("screenHeight", screenHeight)
        json.put("orientationDegrees", orientationDegrees)
        return json
    }
}
