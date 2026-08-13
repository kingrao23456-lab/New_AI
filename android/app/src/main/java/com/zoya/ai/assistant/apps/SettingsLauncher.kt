package com.zoya.ai.assistant.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import com.zoya.ai.assistant.core.model.AutomationResult
import org.json.JSONObject

/**
 * System Settings integration. Opens official Android Settings screens for
 * restricted capabilities instead of trying to bypass restrictions. Brightness
 * and volume are controlled only with APIs Android permits.
 */
class SettingsLauncher(private val context: Context) {

    fun open(page: String): AutomationResult {
        val intent: Intent = when (page.lowercase()) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "mobile", "mobile_network", "network" -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).let {
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            }
            "wireless" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound", "audio" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "notification", "notifications" -> Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS
            ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "apps", "app" -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "permissions" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "date_time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "home" -> Intent(Settings.ACTION_HOME_SETTINGS)
            "gestures" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) // dev settings fallback
            "general" -> Intent(Settings.ACTION_SETTINGS)
            else -> return AutomationResult.unsupported("Unknown settings page '$page'.")
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", page))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open '$page' settings: ${e.message}")
        }
    }

    /** Brightness control: only with WRITE_SETTINGS permission (official API). */
    fun setBrightness(value: Int): AutomationResult {
        if (value !in 0..255) {
            return AutomationResult.blocked("INVALID_VALUE", "Brightness must be between 0 and 255.")
        }
        if (!Settings.System.canWrite(context)) {
            return AutomationResult.permissionDenied(
                "WRITE_SETTINGS",
                "Brightness control requires 'Modify system settings' permission. Opening the official settings screen."
            ).also {
                // Open the official settings for the user to grant it.
                openSystemWriteSettings()
            }
        }
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            AutomationResult.success(JSONObject().put("brightness", value))
        } catch (e: Exception) {
            AutomationResult.failure("BRIGHTNESS_FAILED", "Could not set brightness: ${e.message}")
        }
    }

    fun getBrightness(): AutomationResult {
        val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return AutomationResult.success(JSONObject().put("brightness", value))
    }

    /** Volume control: raises/lowers the music stream using permitted APIs. */
    fun setVolume(level: Int): AutomationResult {
        if (level !in 0..100) {
            return AutomationResult.blocked("INVALID_VALUE", "Volume must be between 0 and 100.")
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = (max * level / 100).coerceAtLeast(0)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            AutomationResult.success(JSONObject().put("volume", level).put("stream", "music"))
        } catch (e: Exception) {
            AutomationResult.failure("VOLUME_FAILED", "Could not set volume: ${e.message}")
        }
    }

    fun getVolume(): AutomationResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        return AutomationResult.success(
            JSONObject()
                .put("volume", if (max > 0) current * 100 / max else 0)
                .put("current", current)
                .put("max", max)
        )
    }

    fun openSystemWriteSettings(): AutomationResult {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", "write_settings"))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open write-settings page: ${e.message}")
        }
    }

    fun openOverlaySettings(): AutomationResult {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("page", "overlay_settings"))
        } catch (e: Exception) {
            AutomationResult.failure("SETTINGS_FAILED", "Could not open overlay settings: ${e.message}")
        }
    }
}
