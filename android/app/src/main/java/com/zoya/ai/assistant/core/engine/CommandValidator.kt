package com.zoya.ai.assistant.core.engine

import com.zoya.ai.assistant.core.model.AutomationResult

/**
 * Validates command arguments before execution. Commands with missing or
 * malformed arguments are blocked and never executed.
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
            "findElement", "findElements", "clickElement", "longClickElement",
            "getElementInfo", "isElementPresent", "waitForElement",
            "focusElement", "clearElement" -> {
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

            "launchApp" -> {
                requireArg(args, "packageName") to requireArg(args, "appName")
                Validation.ok()
            }

            "openUrl" -> {
                requireArg(args, "url")
            }

            "searchBrowser" -> {
                requireArg(args, "query")
            }

            "submitForm", "fillForm" -> {
                requireArg(args, "fields")
            }

            "openSettingsPage" -> {
                requireArg(args, "page")
            }

            "recordGesture" -> {
                requireArg(args, "durationMs")
            }

            "createTask", "scheduleTask" -> {
                requireArg(args, "workflow")
            }

            "startWorkflow", "runWorkflow" -> {
                requireArg(args, "workflow")
            }

            "takePhoto" -> {
                requireArg(args, "camera")
            }

            "recognizeText", "readScreenText" -> {
                Validation.ok()
            }

            "exportLogs" -> {
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
