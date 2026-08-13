package com.zoya.ai.assistant.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.zoya.ai.assistant.core.model.AutomationResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Installed-app management. Lists and searches launchable apps, detects the
 * current application, launches apps, and opens app/system settings pages.
 * Only actions Android officially permits are reported as successful.
 */
class AppManager(private val context: Context) {

    fun currentApp(): AutomationResult {
        val active = CurrentAppTracker.lastActivePackage
        val data = JSONObject()
        data.put("packageName", active ?: JSONObject.NULL)
        if (active != null) {
            data.put("label", labelFor(active))
        }
        return AutomationResult.success(data)
    }

    fun labelFor(packageName: String): String? {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Lists launchable apps, optionally filtered by query. */
    fun listApps(query: String? = null, limit: Int = 200): AutomationResult {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val apps = JSONArray()
        val seen = HashSet<String>()

        val q = query?.trim()?.lowercase()
        var count = 0
        for (ri in resolveInfos) {
            if (count >= limit) break
            val packageName = ri.activityInfo.packageName
            if (!seen.add(packageName)) continue
            val label = ri.loadLabel(pm).toString()
            if (q != null && !packageName.contains(q) && !label.lowercase().contains(q)) continue

            val item = JSONObject()
            item.put("packageName", packageName)
            item.put("name", label)
            item.put("activityName", ri.activityInfo.name)
            item.put("category", categoryFor(packageName))
            item.put("versionName", versionNameFor(packageName))
            item.put("isSystem", isSystemApp(packageName))
            item.put("icon", iconRes(packageName))
            apps.put(item)
            count++
        }
        val data = JSONObject()
        data.put("apps", apps)
        data.put("count", apps.length())
        return AutomationResult.success(data)
    }

    private fun versionNameFor(packageName: String): String? {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun iconRes(packageName: String): Int {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0).icon
        } catch (e: Exception) {
            0
        }
    }

    private fun categoryFor(packageName: String): String {
        return when {
            packageName.contains("whatsapp") || packageName.contains("instagram") ||
                packageName.contains("telegram") || packageName.contains("facebook") ||
                packageName.contains("snapchat") || packageName.contains("discord") -> "Social & Chat"
            packageName.contains("youtube") || packageName.contains("netflix") ||
                packageName.contains("primevideo") || packageName.contains("disney") -> "Video"
            packageName.contains("spotify") || packageName.contains("music") ||
                packageName.contains("gaana") || packageName.contains("wynk") -> "Music"
            packageName.contains("chrome") || packageName.contains("firefox") ||
                packageName.contains("browser") || packageName.contains("opera") -> "Browser"
            packageName.contains("maps") || packageName.contains("navigation") ||
                packageName.contains("uber") || packageName.contains("ola") -> "Navigation"
            packageName.contains("tiktok") || packageName.contains("short") -> "Short Videos"
            packageName.contains("settings") || packageName.contains("systemui") -> "System"
            packageName.contains("camera") -> "Camera"
            packageName.contains("mail") || packageName.contains("gmail") -> "Email"
            else -> "General"
        }
    }

    /** Launches an app by package name. */
    fun launchApp(packageName: String): AutomationResult {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                return AutomationResult.failure("APP_NOT_FOUND", "No launcher intent found for '$packageName'.")
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            AutomationResult.success(data = JSONObject().apply {
                put("packageName", packageName)
                put("label", labelFor(packageName) ?: packageName)
                put("action", "launch")
            })
        } catch (e: Exception) {
            AutomationResult.failure("LAUNCH_FAILED", "Failed to launch '$packageName': ${e.message}")
        }
    }

    /**
     * Resolves an app by its label or package name (case-insensitive) and
     * launches it. Preference order: exact package name → exact label →
     * contains match on label or package. This keeps name resolution fully
     * on the native side so the web layer never falls back to a web page.
     */
    fun launchAppByName(name: String): AutomationResult {
        val pm = context.packageManager
        val q = name.trim().lowercase()
        if (q.isEmpty()) {
            return AutomationResult.failure("MISSING_ARGUMENT", "App name is required.")
        }

        // 1. The name may already be an installed package.
        if (pm.getLaunchIntentForPackage(q) != null) {
            return launchApp(q)
        }

        // 2. Match against the launcher app list.
        val resolveInfos = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
        } catch (e: Exception) {
            emptyList<android.content.pm.ResolveInfo>()
        }

        var best: String? = null
        var exactFound = false
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            val lq = label.lowercase()
            val pq = pkg.lowercase()
            val exact = lq == q || pq == q
            if (exact) {
                best = pkg
                exactFound = true
                break
            }
            if (!exactFound && (lq.contains(q) || pq.contains(q)) && best == null) {
                best = pkg
            }
        }

        if (best != null) {
            return launchApp(best)
        }
        return AutomationResult.failure(
            "APP_NOT_FOUND",
            "No installed app found matching '$name'. Ask the user to install it or try a different name."
        )
    }

    /** Opens the app-info page for an installed app. */
    fun openAppInfo(packageName: String): AutomationResult {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", "app_info").put("packageName", packageName))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open app info: ${e.message}")
        }
    }

    fun openAppPermissions(packageName: String): AutomationResult {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("appops", true)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", "app_permissions").put("packageName", packageName))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open app permissions: ${e.message}")
        }
    }

    fun openNotificationSettings(packageName: String): AutomationResult {
        return try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", "app_notifications").put("packageName", packageName))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open notification settings: ${e.message}")
        }
    }

    fun openBatterySettings(packageName: String): AutomationResult {
        return try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", "battery"))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open battery settings: ${e.message}")
        }
    }

    /** Best-effort stop of an app using the official ACTION_CLOSE_SYSTEM_DIALOGS (only where permitted). */
    fun stopApp(packageName: String): AutomationResult {
        val ownPackage = context.packageName
        if (packageName == ownPackage) {
            return AutomationResult.blocked("SELF_STOP", "Stopping the host app is not permitted.")
        }
        // Android does not allow killing arbitrary apps via public API.
        return AutomationResult.unsupported(
            "Android does not permit stopping '$packageName' from another app. You can open its App Info and force-stop it manually."
        )
    }
}

/**
 * Tracks the currently active foreground package reported by the
 * accessibility service (no usage-statistics permission required).
 */
object CurrentAppTracker {
    @Volatile
    var lastActivePackage: String? = null

    @Volatile
    var lastActiveTime: Long = 0L
}
