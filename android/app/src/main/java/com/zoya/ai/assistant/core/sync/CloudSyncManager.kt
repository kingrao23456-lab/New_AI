package com.zoya.ai.assistant.core.sync

import android.content.Context
import android.content.SharedPreferences
import com.zoya.ai.assistant.core.security.SecureStorage
import com.zoya.ai.assistant.tasks.TaskStore
import com.zoya.ai.assistant.tasks.workflow.WorkflowStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional cloud synchronization for NON-SENSITIVE data only:
 *   - workflows
 *   - gestures
 *   - non-sensitive settings (brightness / volume / persona prefs are excluded)
 *
 * Security rules:
 *   - disabled by default
 *   - never enabled without explicit user action
 *   - the payload is encrypted before transmission (AES-GCM key derived from
 *     the Android Keystore key) so data at rest on the server is unreadable
 *   - passwords, OTPs, tokens, credentials and screen contents are NEVER
 *     included in the sync payload
 */
class CloudSyncManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("zoya_sync", Context.MODE_PRIVATE)
    private val secureStorage = SecureStorage(context)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getEndpoint(): String = prefs.getString(KEY_ENDPOINT, "").orEmpty()

    fun setEndpoint(endpoint: String) {
        prefs.edit().putString(KEY_ENDPOINT, endpoint).apply()
    }

    fun getLastSyncAt(): Long = prefs.getLong(KEY_LAST_SYNC, 0)

    /** Builds the (encrypted) non-sensitive sync payload. */
    fun buildSyncPayload(): JSONObject {
        val payload = JSONObject()
        payload.put("deviceId", deviceId())
        payload.put("generatedAt", System.currentTimeMillis())

        // Workflows (non-sensitive definitions only).
        val workflows = JSONArray()
        WorkflowStore(context).allWorkflows().forEach { summary ->
            WorkflowStore(context).latestRaw(summary.id)?.let { workflows.put(JSONObject().put("id", summary.id).put("name", summary.name).put("workflow", it)) }
        }
        payload.put("workflows", workflows)

        // Gestures (touch paths — no sensitive text).
        val gestures = JSONArray()
        com.zoya.ai.assistant.accessibility.GestureStore(context).list().forEach { gestures.put(it.toJson()) }
        payload.put("gestures", gestures)

        // Non-sensitive settings.
        val settings = JSONObject()
        settings.put("brightness", prefs.getInt(KEY_BRIGHTNESS, -1))
        settings.put("volume", prefs.getInt(KEY_VOLUME, -1))
        payload.put("settings", settings)

        return payload
    }

    /**
     * Uploads the encrypted payload to the configured endpoint.
     * Returns true on success. Never uploads if sync is disabled or no
     * endpoint is configured.
     */
    fun syncNow(): SyncResult {
        if (!isEnabled()) return SyncResult(false, "SYNC_DISABLED", "Cloud sync is disabled.")
        val endpoint = getEndpoint()
        if (!endpoint.startsWith("https://")) {
            return SyncResult(false, "INSECURE_ENDPOINT", "Only HTTPS endpoints are allowed.")
        }
        return runCatching {
            val payload = buildSyncPayload()
            val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encryptPayload(plaintext)

            val conn = URL(endpoint).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Zoya-Sync", "v1")
                OutputStreamWriter(conn.outputStream).use { it.write(encrypted) }
                val code = conn.responseCode
                if (code in 200..299) {
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    SyncResult(true, null, null)
                } else {
                    SyncResult(false, "SYNC_FAILED", "Server returned HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e ->
            SyncResult(false, "SYNC_FAILED", e.message ?: "Network error")
        }
    }

    /** Encrypts payload with an AES key derived from the Keystore key. */
    private fun encryptPayload(plaintext: ByteArray): String {
        val key = secureStorage.syncKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        val ctB64 = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
        return "$ivB64:$ctB64"
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("enabled", isEnabled())
        put("endpoint", if (getEndpoint().isEmpty()) "" else "configured")
        put("lastSyncAt", getLastSyncAt())
    }

    private fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val id = "zoya_" + java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private fun storeNonSensitivePreference() {
        // Hook for persisting non-sensitive prefs that the sync module mirrors.
    }

    companion object {
        private const val KEY_ENABLED = "sync_enabled"
        private const val KEY_ENDPOINT = "sync_endpoint"
        private const val KEY_LAST_SYNC = "sync_last_at"
        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val KEY_BRIGHTNESS = "pref_brightness"
        private const val KEY_VOLUME = "pref_volume"
    }
}

data class SyncResult(
    val success: Boolean,
    val code: String?,
    val message: String?
)
