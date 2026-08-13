package com.zoya.ai.assistant.tasks.workflow

import com.zoya.ai.assistant.accessibility.ScreenContext
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.ResultStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes a multi-step workflow. Supports sequential actions, conditions,
 * loops, repeats, delays, timeouts, variables, branches, retries,
 * verification, cancellation and versioning.
 */
class WorkflowEngine(
    private val runtime: WorkflowRuntime,
    private val maxSteps: Int = 500
) {

    data class RunResult(
        val workflowId: String,
        val success: Boolean,
        val status: ResultStatus,
        val errorMessage: String? = null,
        val stepIndex: Int = -1,
        val stepType: String? = null,
        val stepHistory: List<StepRecord> = emptyList(),
        val variables: Map<String, String> = emptyMap(),
        val durationMs: Long = 0
    )

    data class StepRecord(
        val index: Int,
        val type: String,
        val command: String? = null,
        val success: Boolean,
        val durationMs: Long,
        val errorMessage: String? = null
    )

    /** Mutable state scoped to a single workflow run. */
    private class RunState(
        val history: MutableList<StepRecord>,
        val variables: MutableMap<String, String>,
        var stepCounter: Int
    )

    fun run(workflow: Workflow): RunResult {
        val state = RunState(mutableListOf(), HashMap(), 0)
        val startedAt = System.currentTimeMillis()

        // Track workflow state in screen context for the web layer.
        runtime.screenContext()?.setWorkflowState("running")

        val (ok, status) = runSteps(state, workflow.steps, 0)
        val success = ok && status == ResultStatus.SUCCESS

        runtime.screenContext()?.setWorkflowState(if (success) "completed" else "failed")

        val failedStep = state.history.lastOrNull { !it.success }
        return RunResult(
            workflowId = workflow.id,
            success = success,
            status = status,
            errorMessage = failedStep?.errorMessage,
            stepIndex = state.history.indexOfLast { !it.success },
            stepType = failedStep?.type,
            stepHistory = state.history,
            variables = state.variables,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }

    private fun record(
        state: RunState,
        index: Int,
        type: String,
        command: String?,
        ok: Boolean,
        dur: Long,
        err: String? = null
    ) {
        state.history.add(StepRecord(index, type, command, ok, dur, err))
    }

    private fun runSteps(state: RunState, steps: List<WorkflowStep>, baseIndex: Int): Pair<Boolean, ResultStatus> {
        if (runtime.isCancelled()) return false to ResultStatus.CANCELLED
        for ((i, step) in steps.withIndex()) {
            val (ok, status) = executeStep(state, baseIndex + i, step)
            if (!ok) return false to status
        }
        return true to ResultStatus.SUCCESS
    }

    private fun executeStep(state: RunState, index: Int, step: WorkflowStep): Pair<Boolean, ResultStatus> {
        if (runtime.isCancelled()) return false to ResultStatus.CANCELLED
        if (state.stepCounter++ > maxSteps) return false to ResultStatus.FAILURE
        when (step) {
            is WorkflowStep.Action -> {
                val result = executeActionWithRetries(step)
                record(state, index, "action", step.command, result.ok, 0, result.errorMessage)
            }

            is WorkflowStep.Wait -> {
                if (step.durationMs > 0) {
                    val waited = runtime.sleep(step.durationMs)
                    record(state, index, "wait", null, waited, step.durationMs)
                } else {
                    record(state, index, "wait", null, true, 0)
                }
            }

            is WorkflowStep.SetVariable -> {
                state.variables[step.name] = step.value
                record(state, index, "set_variable", step.name, true, 0)
            }

            is WorkflowStep.If -> {
                val cond = runtime.evaluateCondition(step.condition)
                record(state, index, "if", null, cond, 0)
                val target = if (cond) step.thenSteps else step.elseSteps
                val (ok, status) = runSteps(state, target, index + 1)
                if (!ok) return false to status
            }

            is WorkflowStep.While -> {
                var iterations = 0
                var ok = true
                var status = ResultStatus.SUCCESS
                while (runtime.evaluateCondition(step.condition)) {
                    if (iterations++ >= step.maxIterations) {
                        ok = false
                        status = ResultStatus.FAILURE
                        break
                    }
                    if (runtime.isCancelled()) {
                        ok = false
                        status = ResultStatus.CANCELLED
                        break
                    }
                    val (o, s) = runSteps(state, step.bodySteps, index + 1)
                    if (!o) {
                        ok = false
                        status = s
                        break
                    }
                }
                record(state, index, "while", null, ok, iterations.toLong())
                if (!ok) return false to status
            }

            is WorkflowStep.Repeat -> {
                var ok = true
                var status = ResultStatus.SUCCESS
                for (i in 0 until step.times) {
                    if (runtime.isCancelled()) {
                        ok = false
                        status = ResultStatus.CANCELLED
                        break
                    }
                    val (o, s) = runSteps(state, step.bodySteps, index + 1)
                    if (!o) {
                        ok = false
                        status = s
                        break
                    }
                }
                record(state, index, "repeat", null, ok, 0)
                if (!ok) return false to status
            }

            is WorkflowStep.Timeout -> {
                val start = System.currentTimeMillis()
                // Attempt a wait of durationMs so the deadline is observed
                // for the following steps; on-timeout steps run when the
                // deadline is hit.
                val deadline = step.durationMs
                val waitResult = runtime.sleep(deadline)
                val elapsedWait = System.currentTimeMillis() - start
                val timedOut = elapsedWait >= deadline
                record(state, index, "timeout", null, waitResult && !timedOut, deadline)
                if (timedOut) {
                    val (o, s) = runSteps(state, step.onTimeoutSteps, index + 1)
                    if (!o) return false to s
                }
            }

            is WorkflowStep.Verify -> {
                val passed = runtime.evaluateCondition(step.condition)
                record(state, index, "verify", null, passed, 0)
                if (!passed) {
                    val (o, s) = runSteps(state, step.onFailureSteps, index + 1)
                    if (!o) return false to s
                }
            }

            is WorkflowStep.Branch -> {
                var matched = false
                for ((cond, steps) in step.branches) {
                    if (runtime.evaluateCondition(cond)) {
                        matched = true
                        val (o, s) = runSteps(state, steps, index + 1)
                        if (!o) return false to s
                        break
                    }
                }
                if (!matched) {
                    val (o, s) = runSteps(state, step.defaultSteps, index + 1)
                    if (!o) return false to s
                }
                record(state, index, "branch", null, true, 0)
            }
        }
        return true to ResultStatus.SUCCESS
    }

    private fun executeActionWithRetries(step: WorkflowStep.Action): AutomationResult {
        var attempt = 0
        var lastResult: AutomationResult = AutomationResult.failure("NOT_RUN", "Not executed.")
        while (attempt <= step.retries) {
            attempt++
            if (runtime.isCancelled()) return AutomationResult.cancelled("Workflow cancelled.")
            lastResult = runtime.executeAction(step.command, step.args)

            // Verification after execution when requested.
            if (step.verify && lastResult.ok && step.command in VERIFY_COMMANDS) {
                val verified = runtime.verifyAction(step.command, step.args, lastResult)
                if (!verified) {
                    lastResult = AutomationResult.failure(
                        "VERIFICATION_FAILED",
                        "Action completed but verification failed (attempt $attempt)."
                    )
                }
            }

            if (lastResult.ok) return lastResult
            if (attempt <= step.retries) {
                runtime.sleep(step.retryDelayMs)
            }
        }
        return lastResult
    }

    private fun runWithTimeout(timeoutMs: Long, workflow: Workflow, block: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        val ok = block()
        val elapsed = System.currentTimeMillis() - start
        return if (elapsed < timeoutMs) {
            runtime.sleep((timeoutMs - elapsed).coerceAtLeast(0))
            ok
        } else {
            false
        }
    }

    private companion object {
        val VERIFY_COMMANDS = setOf(
            "launchApp", "openUrl", "clickElement", "typeText", "setText", "scroll", "swipe", "submitForm"
        )
    }
}

/** Runtime capabilities required by the workflow engine. */
interface WorkflowRuntime {
    fun executeAction(command: String, args: JSONObject): AutomationResult
    fun evaluateCondition(condition: Condition): Boolean
    fun verifyAction(command: String, args: JSONObject, result: AutomationResult): Boolean
    fun screenContext(): ScreenContext?
    fun isCancelled(): Boolean
    fun sleep(ms: Long): Boolean
}
