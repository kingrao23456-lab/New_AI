package com.zoya.ai.assistant.privilege

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Advanced privilege detection (Shizuku / device-owner / device lock).
 *
 * IMPORTANT: this module only *detects* whether advanced privileges exist.
 * Zoya never assumes, requests, or silently escalates privileges. Advanced
 * privileges are never required for core functionality — the accessibility
 * automation engine works entirely without them. If a privileged operation is
 * ever needed it must be explicitly authorized by the user first and kept
 * isolated in a dedicated module.
 */
object AdvancedPrivileges {

    /** True when the Shizuku app is installed. Detection only — no binding. */
    fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            val pm = context.packageManager
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        }.getOrDefault(false)
    }

    /** True when this app is the device owner. Requires explicit device-owner setup. */
    fun isDeviceOwner(context: Context): Boolean {
        return runCatching {
            val dm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dm.isDeviceOwnerApp(context.packageName)
        }.getOrDefault(false)
    }

    /** True when a secure device lock (PIN / pattern / password / biometric) is set. */
    fun isDeviceLocked(context: Context): Boolean {
        return runCatching {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.isDeviceSecure
        }.getOrDefault(false)
    }

    /** True when the app is provisioned as a profile owner. */
    fun isProfileOwner(context: Context): Boolean {
        return runCatching {
            val dm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dm.isProfileOwnerApp(context.packageName)
        }.getOrDefault(false)
    }
}
