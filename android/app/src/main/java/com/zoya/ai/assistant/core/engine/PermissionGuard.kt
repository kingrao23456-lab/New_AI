package com.zoya.ai.assistant.core.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zoya.ai.assistant.core.model.AutomationResult

/**
 * Checks whether required runtime permissions / settings are available before
 * an action is executed. Never allows an action to run without its required
 * permission.
 */
object PermissionGuard {

    /**
     * Required Android runtime permissions per automation command family.
     * Map: capability -> required permissions.
     */
    private val CAPABILITY_PERMISSIONS: Map<String, List<String>> = mapOf(
        "camera" to listOf(Manifest.permission.CAMERA),
        "microphone" to listOf(Manifest.permission.RECORD_AUDIO),
        "readMedia" to listOf(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        ),
        "writeMedia" to listOf(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.WRITE_EXTERNAL_STORAGE
        ),
        "notifications" to listOf(Manifest.permission.POST_NOTIFICATIONS)
    )

    fun requiredPermissionsFor(capability: String): List<String> =
        CAPABILITY_PERMISSIONS[capability] ?: emptyList()

    /** Checks runtime permission state without triggering any dialogs. */
    fun isRuntimePermissionGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks that all permissions for a capability are granted.
     * Returns a BLOCKED / PERMISSION_DENIED result listing the missing ones,
     * or null when everything required is granted.
     */
    fun checkCapability(context: Context, capability: String): AutomationResult? {
        val missing = requiredPermissionsFor(capability)
            .filter { !isRuntimePermissionGranted(context, it) }
        if (missing.isEmpty()) return null
        val missingNames = missing.joinToString(", ")
        return AutomationResult.permissionDenied(missingNames)
    }

    /** Special settings-based capabilities. */
    fun isAccessibilityEnabled(context: Context, serviceComponent: String): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(serviceComponent, ignoreCase = true) }
    }

    fun isDisplayOverAppsEnabled(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isNotificationPolicyAccessGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    /** True when this app is exempt from battery optimizations. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
}
