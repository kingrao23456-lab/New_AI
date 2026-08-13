package com.zoya.ai.assistant.core.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * Prevents passwords, OTPs, tokens and other sensitive values from ever being
 * logged, echoed back to the web layer, or stored by the automation modules.
 *
 * Field values identified as sensitive are replaced with "[REDACTED]".
 */
object SecretsRedactor {

    private val SENSITIVE_FIELD_NAMES = listOf(
        "password", "pass", "pwd", "otp", "pin", "token", "secret", "authorization",
        "auth", "apikey", "api_key", "card", "cvv", "ssn", "access_key", "refresh_token"
    )

    private val SENSITIVE_VALUE_PATTERN = Regex(
        "(?i)(password|passwd|pwd|otp|pin|secret|token|cvv|ssn)\\s*[:=]\\s*\\S+"
    )

    fun isSensitiveField(field: String): Boolean {
        val f = field.lowercase()
        return SENSITIVE_FIELD_NAMES.any { f.contains(it) }
    }

    fun redactText(text: String): String {
        if (text.isBlank()) return text
        return SENSITIVE_VALUE_PATTERN.replace(text, "\$1=[REDACTED]")
    }

    fun redactJson(obj: JSONObject): JSONObject {
        val copy = JSONObject(obj.toString())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            when {
                isSensitiveField(key) && value != null -> copy.put(key, "[REDACTED]")
                value is JSONObject -> copy.put(key, redactJson(value))
                value is JSONArray -> copy.put(key, redactJsonArray(value))
            }
        }
        return copy
    }

    private fun redactJsonArray(arr: JSONArray): JSONArray {
        val copy = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.opt(i)
            when (value) {
                is JSONObject -> copy.put(redactJson(value))
                is JSONArray -> copy.put(redactJsonArray(value))
                else -> copy.put(value)
            }
        }
        return copy
    }

    /** Redacts any arguments map before it is passed to executors or logged. */
    fun redactArgs(args: Map<String, Any?>): Map<String, Any?> {
        return args.mapValues { (key, value) ->
            if (isSensitiveField(key) && value != null) {
                "[REDACTED]"
            } else {
                value
            }
        }
    }
}
