package com.zoya.ai.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The app's AccessibilityService. When enabled (by the user in system
 * Settings) it exposes semantic UI access and gesture injection to the
 * automation engine.
 */
class ZoyaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZoyaAccessibility"

        @Volatile
        var instance: ZoyaAccessibilityService? = null
            private set

        @Volatile
        var serviceEnabled: Boolean = false
            private set

        private val listeners = mutableSetOf<AccessibilityEventListener>()

        fun addListener(listener: AccessibilityEventListener) {
            synchronized(listeners) { listeners.add(listener) }
        }

        fun removeListener(listener: AccessibilityEventListener) {
            synchronized(listeners) { listeners.remove(listener) }
        }

        fun serviceComponentName(context: Context): String =
            "${context.packageName}/${ZoyaAccessibilityService::class.java.name}"

        fun isEnabled(context: Context): Boolean {
            val expected = serviceComponentName(context)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** Opens the official Android Accessibility settings for this service. */
        fun openSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open accessibility settings", e)
            }
        }
    }

    interface AccessibilityEventListener {
        fun onServiceConnected(enabled: Boolean) {}
        fun onServiceDisconnected() {}
        fun onWindowChanged(packageName: String?, eventType: Int, eventText: List<CharSequence>?) {}
        fun onGestureRecorded(gestureJson: String?) {}
        fun onNodeSnapshot(nodesJson: String) {}
    }

    val screenContext = ScreenContext()
    lateinit var gestureInjector: GestureInjector
        private set

    @Volatile
    var gestureRecorder: GestureRecorder? = null
        private set

    fun registerGestureRecorder(recorder: GestureRecorder) {
        gestureRecorder = recorder
        notificationHelper.showRecordingNotification()
    }

    fun unregisterGestureRecorder() {
        gestureRecorder = null
        notificationHelper.cancelRecordingNotification()
    }

    private val notificationHelper: NotificationHelper by lazy { NotificationHelper(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceEnabled = true
        gestureInjector = GestureInjector(this, { screenWidth() }, { screenHeight() })

        val info = serviceInfo
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info

        notificationHelper.showServiceActiveNotification()
        synchronized(listeners) {
            listeners.forEach { it.onServiceConnected(true) }
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()

            // Event-driven updates: only rebuild the interactive-window snapshot
            // on window state changes to keep CPU/battery usage minimal. Content
            // changed / scrolled events just refresh package context cheaply.
            val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val root = if (isWindowChange) rootInActiveWindow else null

            screenContext.updateFromEvent(
                packageName = packageName,
                className = className,
                root = root,
                screenW = screenWidth(),
                screenH = screenHeight(),
                orientation = screenOrientationDegrees()
            )
            root?.recycle()

            synchronized(listeners) {
                listeners.forEach { it.onWindowChanged(packageName, event.eventType, event.text?.toList()) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onAccessibilityEvent error", e)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(listeners) {
            listeners.forEach { it.onServiceDisconnected() }
        }
        instance = null
        serviceEnabled = false
        notificationHelper.cancelServiceNotification()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        serviceEnabled = false
        notificationHelper.cancelServiceNotification()
        super.onDestroy()
    }

    private fun screenWidth(): Int {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        return metrics.widthPixels
    }

    private fun screenHeight(): Int {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    private fun screenOrientationDegrees(): Int {
        val display = (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        return display?.rotation?.let { it * 90 } ?: 0
    }

    fun performGlobalActionCompat(action: Int): Boolean = performGlobalAction(action)

    /** Recycles cached root when the window is gone to avoid leaks. */
    fun resetScreenContext() {
        screenContext.recycleRoot()
    }

    /** Releases cached buffers/roots under memory pressure. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            screenContext.recycleRoot()
        }
    }
}
