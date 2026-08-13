package com.zoya.ai.assistant.tasks.workflow

import com.zoya.ai.assistant.core.model.Selector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workflow definition model. A workflow is a versioned sequence of steps
 * supporting actions, conditions, loops, repeats, delays, timeouts,
 * variables, branches, retries and verification.
 */
data class Workflow(
    val id: String,
    val name: String,
    val version: Int,
    val steps: List<WorkflowStep>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

sealed class WorkflowStep {
    data class Action(
        val command: String,
        val args: JSONObject,
        val retries: Int = 0,
        val retryDelayMs: Long = 1000,
        val timeoutMs: Long = 15000,
        val verify: Boolean = true
    ) : WorkflowStep()

    data class If(
        val condition: Condition,
        val thenSteps: List<WorkflowStep>,
        val elseSteps: List<WorkflowStep> = emptyList()
    ) : WorkflowStep()

    data class While(
        val condition: Condition,
        val bodySteps: List<WorkflowStep>,
        val maxIterations: Int = 10
    ) : WorkflowStep()

    data class Repeat(
        val times: Int,
        val bodySteps: List<WorkflowStep>
    ) : WorkflowStep()

    data class Wait(val durationMs: Long) : WorkflowStep()

    data class Timeout(
        val durationMs: Long,
        val onTimeoutSteps: List<WorkflowStep> = emptyList()
    ) : WorkflowStep()

    data class Verify(
        val condition: Condition,
        val onFailureSteps: List<WorkflowStep> = emptyList()
    ) : WorkflowStep()

    data class SetVariable(val name: String, val value: String) : WorkflowStep()

    data class Branch(
        val branches: List<Pair<Condition, List<WorkflowStep>>>,
        val defaultSteps: List<WorkflowStep> = emptyList()
    ) : WorkflowStep()
}

sealed class Condition {
    data class PackageIs(val packageName: String) : Condition()
    data class TextVisible(val text: String, val partial: Boolean = true) : Condition()
    data class OcrContains(val text: String) : Condition()
    data class AccessibilityNode(val selector: Selector) : Condition()
    data class ScreenState(val state: String) : Condition()
    data class Variable(val name: String, val equals: String?) : Condition()
    data class And(val conditions: List<Condition>) : Condition()
    data class Or(val conditions: List<Condition>) : Condition()
    data class Not(val condition: Condition) : Condition()
}

/**
 * Parses a workflow from its JSON string representation. Unsupported or
 * malformed steps are rejected rather than silently dropped.
 */
object WorkflowParser {

    fun parse(raw: String): Workflow {
        val json = JSONObject(raw)
        val id = json.optString("id", "workflow_" + System.currentTimeMillis())
        val name = json.optString("name", "Untitled Workflow")
        val version = json.optInt("version", 1)
        val steps = parseSteps(json.optJSONArray("steps") ?: JSONArray())
        return Workflow(id, name, version, steps)
    }

    fun parseSteps(arr: JSONArray): List<WorkflowStep> {
        val steps = mutableListOf<WorkflowStep>()
        for (i in 0 until arr.length()) {
            val stepJson = arr.getJSONObject(i)
            steps.add(parseStep(stepJson))
        }
        return steps
    }

    private fun parseStep(json: JSONObject): WorkflowStep {
        val type = json.optString("type", "action").lowercase()
        return when (type) {
            "action" -> WorkflowStep.Action(
                command = json.optString("command", ""),
                args = json.optJSONObject("args") ?: JSONObject(),
                retries = json.optInt("retries", 0),
                retryDelayMs = json.optLong("retryDelayMs", 1000),
                timeoutMs = json.optLong("timeoutMs", 15000),
                verify = json.optBoolean("verify", true)
            )

            "if" -> WorkflowStep.If(
                condition = parseCondition(json.optJSONObject("condition") ?: JSONObject()),
                thenSteps = parseSteps(json.optJSONArray("then") ?: JSONArray()),
                elseSteps = parseSteps(json.optJSONArray("else") ?: JSONArray())
            )

            "while" -> WorkflowStep.While(
                condition = parseCondition(json.optJSONObject("condition") ?: JSONObject()),
                bodySteps = parseSteps(json.optJSONArray("body") ?: JSONArray()),
                maxIterations = json.optInt("maxIterations", 10)
            )

            "repeat" -> WorkflowStep.Repeat(
                times = json.optInt("times", 1).coerceAtLeast(1),
                bodySteps = parseSteps(json.optJSONArray("body") ?: JSONArray())
            )

            "wait", "delay" -> WorkflowStep.Wait(json.optLong("durationMs", json.optLong("delayMs", 1000)))

            "timeout" -> WorkflowStep.Timeout(
                durationMs = json.optLong("durationMs", 10000),
                onTimeoutSteps = parseSteps(json.optJSONArray("onTimeout") ?: JSONArray())
            )

            "verify" -> WorkflowStep.Verify(
                condition = parseCondition(json.optJSONObject("condition") ?: JSONObject()),
                onFailureSteps = parseSteps(json.optJSONArray("onFailure") ?: JSONArray())
            )

            "setvariable", "set_variable", "var" -> WorkflowStep.SetVariable(
                name = json.optString("name", ""),
                value = json.optString("value", "")
            )

            "branch" -> {
                val branches = mutableListOf<Pair<Condition, List<WorkflowStep>>>()
                val branchArr = json.optJSONArray("branches") ?: JSONArray()
                for (i in 0 until branchArr.length()) {
                    val b = branchArr.getJSONObject(i)
                    branches.add(
                        parseCondition(b.optJSONObject("condition") ?: JSONObject()) to
                            parseSteps(b.optJSONArray("steps") ?: JSONArray())
                    )
                }
                WorkflowStep.Branch(branches, parseSteps(json.optJSONArray("default") ?: JSONArray()))
            }

            else -> throw IllegalArgumentException("Unsupported workflow step type: '$type'")
        }
    }

    fun parseCondition(json: JSONObject): Condition {
        val type = json.optString("type", "unknown").lowercase()
        return when (type) {
            "package_is" -> Condition.PackageIs(json.optString("packageName", ""))
            "text_visible", "text" -> Condition.TextVisible(json.optString("text", ""), json.optBoolean("partial", true))
            "ocr_contains", "ocr" -> Condition.OcrContains(json.optString("text", ""))
            "accessibility_node", "node" -> Condition.AccessibilityNode(
                Selector.fromJson(json.optJSONObject("selector") ?: JSONObject())
            )
            "screen_state" -> Condition.ScreenState(json.optString("state", ""))
            "variable" -> Condition.Variable(
                json.optString("name", ""),
                if (json.has("equals") && !json.isNull("equals")) json.optString("equals") else null
            )
            "and" -> Condition.And(parseConditionArray(json.optJSONArray("conditions") ?: JSONArray()))
            "or" -> Condition.Or(parseConditionArray(json.optJSONArray("conditions") ?: JSONArray()))
            "not" -> Condition.Not(parseCondition(json.optJSONObject("condition") ?: JSONObject()))
            else -> throw IllegalArgumentException("Unsupported condition type: '$type'")
        }
    }

    private fun parseConditionArray(arr: JSONArray): List<Condition> {
        val list = mutableListOf<Condition>()
        for (i in 0 until arr.length()) {
            list.add(parseCondition(arr.getJSONObject(i)))
        }
        return list
    }
}
