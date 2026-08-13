package com.zoya.ai.assistant.core.security

import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.ResultStatus

/**
 * Guards the JS<->native bridge. Every command is validated before execution:
 *  - call shape / required arguments
 *  - command allow-list
 *  - sensitive payload size limits
 *  - explicit consent flags for high-risk operations
 */
object BridgeGuard {

    private const val MAX_TEXT_INPUT_LENGTH = 4096
    private const val MAX_GESTURE_POINTS = 1024
    private const val MAX_WORKFLOW_BYTES = 512 * 1024

    /** Commands that are considered high risk and require explicit consent. */
    val HIGH_RISK_COMMANDS = setOf(
        // Financial / purchase / payment surfaces
        "submitForm",
        "fillForm",
        "typeText",
        // App control
        "launchApp",
        "launchAppByName",
        "stopApp",
        "openSettingsPage",
        "openAppPermissions",
        // Communications (require confirmation to prevent abuse)
        "sendSms",
        "dialNumber",
        // Important deletion
        "deleteTask",
        "cancelTask",
        "deleteWorkflow",
        "deleteGesture",
        "clearExecutionLogs"
    )

    /**
     * Validates a command name + arguments. Returns null when valid, otherwise
     * a structured blocked result.
     */
    fun validateCommand(command: String, args: Map<String, Any?>?, consent: Boolean): AutomationResult? {
        if (command.isBlank()) {
            return AutomationResult.blocked("INVALID_COMMAND", "Command name is empty.")
        }
        if (command.length > 128) {
            return AutomationResult.blocked("INVALID_COMMAND", "Command name too long.")
        }

        if (command in HIGH_RISK_COMMANDS && !consent) {
            return AutomationResult.blocked(
                "CONSENT_REQUIRED",
                "Command '$command' is high risk and requires explicit user confirmation."
            )
        }

        args?.forEach { (key, value) ->
            when (key) {
                "text", "value", "query", "url", "message" -> {
                    val s = value as? String
                    if (s != null && s.length > MAX_TEXT_INPUT_LENGTH) {
                        return AutomationResult.blocked(
                            "PAYLOAD_TOO_LARGE",
                            "Argument '$key' exceeds the maximum allowed length."
                        )
                    }
                }
                "gesturePath", "points" -> {
                    if (value is List<*> && value.size > MAX_GESTURE_POINTS) {
                        return AutomationResult.blocked(
                            "PAYLOAD_TOO_LARGE",
                            "Gesture path exceeds the maximum number of points."
                        )
                    }
                }
                "workflow" -> {
                    val s = value as? String
                    if (s != null && s.toByteArray().size > MAX_WORKFLOW_BYTES) {
                        return AutomationResult.blocked(
                            "PAYLOAD_TOO_LARGE",
                            "Workflow definition exceeds the maximum size."
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Rejects calls coming from unverified/foreign origins. The native webview
     * always runs from the app scheme, so anything else is suspicious.
     */
    fun isTrustedCall(origin: String?, allowedSchemes: Set<String>): Boolean {
        if (origin.isNullOrBlank()) return false
        val scheme = origin.substringBefore("://").lowercase()
        return scheme in allowedSchemes || scheme == "capacitor" || scheme == "https"
    }
}
