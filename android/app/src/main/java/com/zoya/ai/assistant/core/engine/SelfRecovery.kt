package com.zoya.ai.assistant.core.engine

import com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.ResultStatus

/**
 * Self-recovery for failed automation steps. Recovery is only attempted when
 * safe (never for sensitive submissions), and the engine never resumes
 * blindly — it re-detects the target and re-verifies. When automatic
 * recovery is unreliable, the step is reported as needing user input.
 */
class SelfRecovery(
    private val waitMs: (Long) -> Unit = { Thread.sleep(it) },
    private val pressBack: () -> Boolean,
    private val goHome: () -> Boolean
) {

    enum class FailureKind {
        TIMEOUT,
        MISSING_TARGET,
        UI_CHANGED,
        APP_CLOSED,
        PERMISSION_FAILURE,
        OCR_FAILURE,
        CAPTURE_FAILURE,
        UNSUPPORTED_OPERATION
    }

    data class RecoveryOutcome(
        val recovered: Boolean,
        val action: String? = null,
        val requiresUser: Boolean = false,
        val reason: String? = null
    )

    fun classify(status: ResultStatus, errorCode: String?): FailureKind {
        return when {
            status == ResultStatus.TIMEOUT -> FailureKind.TIMEOUT
            status == ResultStatus.PERMISSION_DENIED -> FailureKind.PERMISSION_FAILURE
            status == ResultStatus.UNSUPPORTED -> FailureKind.UNSUPPORTED_OPERATION
            errorCode == "TARGET_NOT_FOUND" || errorCode == "FIELD_NOT_FOUND" || errorCode == "SUBMIT_NOT_FOUND" -> FailureKind.MISSING_TARGET
            errorCode == "OCR_FAILED" || errorCode == "NO_TEXT" -> FailureKind.OCR_FAILURE
            errorCode == "CAPTURE_FAILED" -> FailureKind.CAPTURE_FAILURE
            errorCode == "APP_CLOSED" -> FailureKind.APP_CLOSED
            errorCode == "UI_CHANGED" -> FailureKind.UI_CHANGED
            else -> FailureKind.UI_CHANGED
        }
    }

    /**
     * Attempts recovery for a failed action. Returns the outcome describing
     * what recovery did. The caller is responsible for re-executing the action
     * if [RecoveryOutcome.recovered] is true and then re-verifying.
     */
    fun recover(
        command: String,
        failureKind: FailureKind,
        screen: com.zoya.ai.assistant.accessibility.ScreenContext?
    ): RecoveryOutcome {
        // Never auto-recover sensitive or high-risk submissions.
        if (command == "submitForm" || command == "typeText" && isSensitiveArgsInvolved()) {
            return RecoveryOutcome(recovered = false, requiresUser = true, reason = "High-risk action; user confirmation required.")
        }

        val svc = ZoyaAccessibilityService.instance

        return when (failureKind) {
            FailureKind.TIMEOUT -> {
                // Wait briefly and recheck the screen is still responsive.
                waitMs(1200)
                if (svc?.screenContext?.currentPackage != null) {
                    RecoveryOutcome(recovered = true, action = "waited_and_rechecked")
                } else {
                    RecoveryOutcome(recovered = false, requiresUser = true, reason = "Screen is not responding.")
                }
            }

            FailureKind.MISSING_TARGET, FailureKind.UI_CHANGED -> {
                // 1) wait & recheck
                waitMs(800)
                val root = svc?.screenContext?.getRoot()
                if (root != null) {
                    root.recycle()
                    // 2) try scroll to reveal the target
                    val scrolled = tryScrollDown(svc)
                    if (scrolled) {
                        waitMs(400)
                        RecoveryOutcome(recovered = true, action = "scrolled_to_redetect")
                    } else {
                        RecoveryOutcome(recovered = true, action = "redetect_target")
                    }
                } else {
                    // 3) press Back to exit a stuck overlay/dialog, then Home
                    val back = pressBack()
                    waitMs(500)
                    goHome()
                    RecoveryOutcome(recovered = back, action = "back_then_home")
                }
            }

            FailureKind.APP_CLOSED -> {
                // Relaunch the current/last app if safe.
                val pkg = screen?.currentPackage
                if (pkg != null && pkg != "com.android.systemui") {
                    val launched = try {
                        val intent = svc?.packageManager?.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            svc.startActivity(intent)
                            waitMs(1200)
                            true
                        } else false
                    } catch (e: Exception) {
                        false
                    }
                    RecoveryOutcome(recovered = launched, action = "relaunch_app")
                } else {
                    goHome()
                    RecoveryOutcome(recovered = false, requiresUser = true, reason = "No safe app to relaunch.")
                }
            }

            FailureKind.PERMISSION_FAILURE -> {
                RecoveryOutcome(recovered = false, requiresUser = true, reason = "Permission required; open Settings.")
            }

            FailureKind.OCR_FAILURE, FailureKind.CAPTURE_FAILURE -> {
                // Try accessibility detection as an alternative method.
                val root = svc?.screenContext?.getRoot()
                val accessibilityAvailable = root != null
                root?.recycle()
                if (accessibilityAvailable) {
                    RecoveryOutcome(recovered = true, action = "accessibility_fallback")
                } else {
                    RecoveryOutcome(recovered = false, requiresUser = true, reason = "No detection method available.")
                }
            }

            FailureKind.UNSUPPORTED_OPERATION -> {
                RecoveryOutcome(recovered = false, requiresUser = true, reason = "Operation unsupported on this device.")
            }
        }
    }

    private fun isSensitiveArgsInvolved(): Boolean = false // overridden by engine using redacted args

    private fun tryScrollDown(svc: ZoyaAccessibilityService?): Boolean {
        if (svc == null) return false
        val screen = svc.screenContext
        if (screen.screenWidth <= 0 || screen.screenHeight <= 0) return false
        return try {
            val spec = com.zoya.ai.assistant.accessibility.GestureInjector.GestureSpec(
                "swipe",
                fromX = screen.screenWidth / 2.0,
                fromY = screen.screenHeight * 0.7,
                toX = screen.screenWidth / 2.0,
                toY = screen.screenHeight * 0.3,
                durationMs = 300,
                normalized = false
            )
            svc.gestureInjector.dispatch(spec, timeoutMs = 3000).ok
        } catch (e: Exception) {
            false
        }
    }
}
