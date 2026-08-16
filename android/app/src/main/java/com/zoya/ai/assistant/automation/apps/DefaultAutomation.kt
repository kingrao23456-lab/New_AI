package com.zoya.ai.assistant.automation.apps

import com.zoya.ai.assistant.automation.AppAutomation
import com.zoya.ai.assistant.core.engine.AutomationEngine
import com.zoya.ai.assistant.core.model.AutomationResult

/**
 * Generic fallback automation. Used for any app that doesn't have its own
 * dedicated automation file yet (registered per-app files always take
 * priority — see AutomationRegistry.forPackage). Implements every UI
 * automation command using the original generic engine (layered
 * accessibility detection: text / content-description / resource-id /
 * class, plus coordinate gestures).
 *
 * This is intentionally the LAST thing registered. Per-app files should be
 * built and tested first because they give far more reliable results for
 * that specific app; this exists purely as a safety net so apps without a
 * dedicated file (or commands a dedicated file doesn't implement) still get
 * best-effort automation instead of a hard "NO_AUTOMATION" block.
 */
class DefaultAutomation : AppAutomation {

    // Not tied to one app — only used via AutomationRegistry.registerDefault().
    override val packageName: String = "*"

    override fun handles(command: String): Boolean = true

    override fun execute(
        engine: AutomationEngine,
        command: String,
        args: Map<String, Any?>,
        timeoutMs: Long
    ): AutomationResult = engine.runLegacyDispatch(command, args, timeoutMs)
}
