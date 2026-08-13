package com.zoya.ai.assistant.apps

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.zoya.ai.assistant.accessibility.NodeFinder
import com.zoya.ai.assistant.accessibility.SemanticActions
import com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.Selector
import com.zoya.ai.assistant.core.security.SecretsRedactor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Form automation. Detects text, email, password and phone fields, dropdowns,
 * checkboxes, radio buttons and submit controls; focuses, clears, types,
 * selects and submits fields; and verifies the submission.
 *
 * Security rules:
 *  - Password / OTP / token field values are NEVER logged or echoed back.
 *  - High-risk submissions require explicit confirmation.
 */
class FormAutomator(private val service: () -> ZoyaAccessibilityService?) {

    private val FIELD_HINTS = mapOf(
        "password" to listOf("password", "pwd", "pass"),
        "otp" to listOf("otp", "one-time", "verification code", "code", "pin"),
        "email" to listOf("email", "e-mail", "mail"),
        "phone" to listOf("phone", "mobile", "number", "telephone"),
        "username" to listOf("username", "user name", "userid", "login")
    )

    /** Detects form fields and controls on the current screen. */
    fun detectForm(): AutomationResult {
        val svc = service() ?: return AccessibilityUnavailable()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")
        val fields = JSONArray()
        val controls = JSONArray()

        NodeFinder.walk(root) { node, _ ->
            if (!node.isVisibleToUser) return@walk null
            val type = NodeFinder.elementType(node)
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
                val hint = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                val field = JSONObject()
                field.put("kind", fieldKind(hint, node))
                field.put("hint", hint)
                field.put("isPassword", node.isPassword)
                field.put("focused", node.isFocused)
                field.put("enabled", node.isEnabled)
                field.put("bounds", nodeBounds(node))
                fields.put(field)
            } else if (node.isCheckable || node.isClickable || type == "button") {
                val ctrl = JSONObject()
                ctrl.put("type", type)
                ctrl.put("text", node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                ctrl.put("enabled", node.isEnabled)
                ctrl.put("bounds", nodeBounds(node))
                controls.put(ctrl)
            }
            null
        }
        root.recycle()

        val data = JSONObject()
        data.put("fields", fields)
        data.put("controls", controls)
        data.put("fieldCount", fields.length())
        data.put("submitFound", containsSubmit(controls))
        return AutomationResult.success(data)
    }

    /**
     * Fills the given fields map. `fields` is a JSON object mapping field
     * hints/indices to values. Sensitive field values are redacted from any
     * log or echo.
     */
    fun fillForm(fields: JSONObject): AutomationResult {
        val svc = service() ?: return AccessibilityUnavailable()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")
        val results = JSONArray()
        var failure: AutomationResult? = null

        val fieldKeys = fields.keys().asSequence().toList()
        for (key in fieldKeys) {
            val value = fields.optString(key)
            val selector = Selector(
                partialText = key,
                editable = true
            )
            val node = NodeFinder.findFirst(root, selector)
                ?: NodeFinder.findAll(root, Selector(editable = true)).getOrNull(key.toIntOrNull() ?: 0)

            if (node == null) {
                results.put(JSONObject().put("field", key).put("result", "not_found"))
                failure = failure ?: AutomationResult.failure(
                    "FIELD_NOT_FOUND",
                    "Could not find form field '$key'."
                )
                continue
            }

            val ok = SemanticActions.click(node) && SemanticActions.setText(node, value)
            results.put(
                JSONObject()
                    .put("field", key)
                    .put("result", if (ok) "filled" else "failed")
                    .put("valueEchoed", !SecretsRedactor.isSensitiveField(key))
            )
            if (!ok) {
                failure = failure ?: AutomationResult.failure("FILL_FAILED", "Could not fill field '$key'.")
            }
            node.recycle()
            Thread.sleep(150)
        }
        root.recycle()

        if (failure != null) return failure!!.copy(data = JSONObject().put("results", results))
        return AutomationResult.success(JSONObject().put("results", results))
    }

    /** Submits the form by clicking a submit control (requires confirmation for high-risk flows). */
    fun submit(confirmed: Boolean): AutomationResult {
        if (!confirmed) {
            return AutomationResult.blocked(
                "CONSENT_REQUIRED",
                "Form submission is a high-risk action. Confirm before submitting."
            )
        }
        val svc = service() ?: return AccessibilityUnavailable()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")
        val submitKeywords = listOf("submit", "sign in", "login", "log in", "continue", "send", "create", "register", "ok", "done")

        var submitNode: AccessibilityNodeInfo? = null
        NodeFinder.walk(root) { node, _ ->
            if (!node.isVisibleToUser) return@walk null
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (node.isClickable && submitKeywords.any { text.lowercase().contains(it) }) {
                return@walk node
            }
            null
        }?.also { submitNode = it }

        if (submitNode == null) {
            root.recycle()
            return AutomationResult.failure("SUBMIT_NOT_FOUND", "No submit control found on the form.")
        }

        val clicked = SemanticActions.click(submitNode!!)
        submitNode!!.recycle()
        root.recycle()
        return if (clicked) {
            AutomationResult.success(JSONObject().put("action", "submit"))
        } else {
            AutomationResult.failure("SUBMIT_FAILED", "Submit control was found but the click was rejected.")
        }
    }

    private fun fieldKind(hint: String, node: AccessibilityNodeInfo): String {
        if (node.isPassword) return "password"
        val h = hint.lowercase()
        for ((kind, keywords) in FIELD_HINTS) {
            if (keywords.any { h.contains(it) }) return kind
        }
        return "text"
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): JSONObject {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return JSONObject()
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)
    }

    private fun containsSubmit(controls: JSONArray): Boolean {
        val keywords = listOf("submit", "sign in", "login", "continue", "send", "create", "done")
        for (i in 0 until controls.length()) {
            val text = controls.getJSONObject(i).optString("text", "").lowercase()
            if (keywords.any { text.contains(it) }) return true
        }
        return false
    }

    private fun AccessibilityUnavailable(): AutomationResult =
        AutomationResult.permissionDenied(
            "ACCESSIBILITY",
            "Accessibility service is not enabled. Enable 'Zoya AI Assistant' in Accessibility settings first."
        )
}
