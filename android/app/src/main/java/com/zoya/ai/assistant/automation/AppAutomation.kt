package com.zoya.ai.assistant.automation

import com.zoya.ai.assistant.core.engine.AutomationEngine
import com.zoya.ai.assistant.core.model.AutomationResult

/**
 * Contract for a single app's dedicated automation file (e.g. WhatsApp,
 * Instagram). Each app gets its own isolated file under automation/apps/
 * so fixing or tuning one app's automation never touches another app's.
 *
 * If no per-app file handles a command for the current foreground app,
 * AutomationEngine falls back to whatever is registered as the DEFAULT
 * automation in AutomationRegistry (added last, once all per-app files
 * are done and tested).
 */
interface AppAutomation {

    /** The Android package name this automation is for, e.g. "com.whatsapp". */
    val packageName: String

    /** Return true if this file implements the given command for its app. */
    fun handles(command: String): Boolean

    /** Execute the command. Only called when handles(command) is true. */
    fun execute(
        engine: AutomationEngine,
        command: String,
        args: Map<String, Any?>,
        timeoutMs: Long
    ): AutomationResult
}
