package com.zoya.ai.assistant.core.engine

import com.zoya.ai.assistant.core.model.AutomationResult

/**
 * Validates command arguments before execution. Commands with missing or
 * malformed arguments are blocked and never executed.
 *
 * IMPORTANT: every command name handled in AutomationEngine.dispatch() must
 * have a corresponding branch here (even if it's just Validation.ok()),
 * otherwise it gets rejected as UNKNOWN_COMMAND before it ever reaches
 * dispatch(). Keep this list in sync with the `when (command)` block in
 * AutomationEngine.kt.
 */
object CommandValidator {

    data class Validation(
        val valid: Boolean,
        val result: AutomationResult? = null
    ) {
        companion object {
            fun ok() = Validation(true)
            fun invalid(result: AutomationResult) = Validation(false, result)
        }
    }

    fun validate(command: String, args: Map<String, Any?>): Validation {
        return when (command) {
            // ---- element discovery & interaction (need a selector) ----
            "findElement", "findElements", "clickElement", "longClickElement",
            "getElementInfo", "isElementPresent", "waitForElement",
            "focusElement", "clearElement", "toggleElement", "setChecked",
            "scrollElement", "dismissElement" -> {
                requireSelector(args)
            }

            "typeText", "setText" -> {
                requireArg(args, "text")
            }

            "tapCoordinate", "longPressCoordinate" -> {
                requireArg(args, "x") to requireArg(args, "y")
                Validation.ok()
            }

            "swipe", "scroll" -> {
                requireArg(args, "direction")
            }

            "gesturePath" -> {
                requireArg(args, "points")
            }

            "pinch", "zoom", "doubleTap" -> {
                Validation.ok()
            }

            // ---- gesture recorder ----
            "recordGesture" -> {
                requireArg(args, "durationMs")
            }
            "stopGestureRecording", "listGestures", "getGesture", "saveGesture",
            "renameGesture", "duplicateGesture", "deleteGesture", "importGesture",
            "exportGesture", "replayGesture" -> {
                Validation.ok()
            }

            // ---- apps ----
            "launchApp" -> {
                requireArg(args, "packageName") to requireArg(args, "appName")
                Validation.ok()
            }
            "launchAppByName", "listApps", "currentApp", "openAppInfo",
            "openAppPermissions", "openNotificationSettings", "openBatterySettings",
            "stopApp" -> {
                Validation.ok()
            }

            // ---- browser ----
            "openUrl" -> {
                requireArg(args, "url")
            }
            "searchBrowser", "searchInBrowser" -> {
                requireArg(args, "query")
            }
            "readVisibleText", "clickLink", "browserScroll", "verifyNavigation" -> {
                Validation.ok()
            }

            // ---- forms ----
            "submitForm", "fillForm" -> {
                requireArg(args, "fields")
            }
            "detectForm" -> {
                Validation.ok()
            }

            // ---- settings ----
            "openSettingsPage" -> {
                requireArg(args, "page")
            }
            "setBrightness", "getBrightness", "setVolume", "getVolume" -> {
                Validation.ok()
            }

            // ---- accessibility service ----
            "accessibilityStatus", "globalAction", "pressBack", "pressHome",
            "openAccessibilitySettings" -> {
                Validation.ok()
            }

            // ---- camera / microphone ----
            "takePhoto" -> {
                requireArg(args, "camera")
            }
            "cameraPermissionStatus", "startRecording", "stopRecording",
            "micPermissionStatus", "microphoneStatus" -> {
                Validation.ok()
            }

            // ---- vision / OCR ----
            "recognizeText", "readScreenText", "ocrScreen", "performOCR",
            "visualDetect", "screenCaptureStatus" -> {
                Validation.ok()
            }

            // ---- state ----
            "getScreenContext" -> {
                Validation.ok()
            }

            // ---- system status / logs / security ----
            "getAutomationStatus", "getDeviceCapabilities", "getExecutionLogs",
            "exportLogs", "clearExecutionLogs", "getPermissionStatus",
            "getSecurityStatus", "isBiometricAvailable", "biometricStatus",
            "startAutomation", "stopAutomation", "getActiveWorkflow" -> {
                Validation.ok()
            }

            // ---- cloud sync ----
            "getSyncStatus", "setSyncEnabled", "setSyncEndpoint", "syncNow" -> {
                Validation.ok()
            }

            // ---- tasks & workflows ----
            "createTask", "scheduleTask" -> {
                requireArg(args, "workflow")
            }
            "startWorkflow", "runWorkflow" -> {
                requireArg(args, "workflow")
            }
            "listTasks", "updateTask", "deleteTask", "cancelTask", "enableTask",
            "disableTask", "taskHistory", "executeTask" -> {
                Validation.ok()
            }

            // ---- workflow store (versioned) ----
            "saveWorkflow", "listWorkflows", "getWorkflow", "workflowVersions",
            "restoreWorkflowVersion", "deleteWorkflow" -> {
                Validation.ok()
            }

            else -> {
                // Unknown commands are rejected outright rather than executed blindly.
                Validation.invalid(
                    AutomationResult.blocked("UNKNOWN_COMMAND", "Unknown or unsupported command: '$command'")
                )
            }
        }
    }

    private fun requireSelector(args: Map<String, Any?>): Validation {
        fun has(key: String): Boolean = !(args[key] as? CharSequence).isNullOrBlank()
        val hasExact = has("exactText")
        val hasPartial = has("partialText")
        val hasRegex = has("regexText")
        val hasCd = has("contentDescription")
        val hasCdPartial = has("contentDescriptionPartial")
        val hasRes = has("resourceId")
        val hasClass = has("className")
        if (!hasExact && !hasPartial && !hasRegex && !hasCd && !hasCdPartial && !hasRes && !hasClass) {
            return Validation.invalid(
                AutomationResult.blocked("INVALID_SELECTOR", "A target selector (text / content-description / resource-id / class) is required.")
            )
        }
        return Validation.ok()
    }

    private fun requireArg(args: Map<String, Any?>, key: String): Validation {
        val value = args[key]
        if (value == null || (value is String && value.isBlank())) {
            return Validation.invalid(
                AutomationResult.blocked("MISSING_ARGUMENT", "Required argument '$key' is missing or empty.")
            )
        }
        return Validation.ok()
    }
}

/** Little helper so the two-arg `to` chaining above reads naturally. */
private infix fun CommandValidator.Validation.to(other: CommandValidator.Validation): CommandValidator.Validation =
    if (!this.valid) this else other
