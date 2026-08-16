package com.zoya.ai.assistant.core.engine

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import com.zoya.ai.assistant.accessibility.GestureInjector
import com.zoya.ai.assistant.accessibility.GestureRecorder
import com.zoya.ai.assistant.accessibility.GestureStore
import com.zoya.ai.assistant.accessibility.NodeFinder
import com.zoya.ai.assistant.accessibility.ScreenContext
import com.zoya.ai.assistant.accessibility.SemanticActions
import com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService
import com.zoya.ai.assistant.apps.AppManager
import com.zoya.ai.assistant.apps.BrowserAutomator
import com.zoya.ai.assistant.apps.FormAutomator
import com.zoya.ai.assistant.apps.SettingsLauncher
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.ResultStatus
import com.zoya.ai.assistant.core.model.Selector
import com.zoya.ai.assistant.core.security.BridgeGuard
import com.zoya.ai.assistant.core.security.SecretsRedactor
import com.zoya.ai.assistant.media.CameraController
import com.zoya.ai.assistant.media.MicrophoneController
import com.zoya.ai.assistant.tasks.TaskScheduler
import com.zoya.ai.assistant.tasks.TaskStore
import com.zoya.ai.assistant.tasks.workflow.WorkflowEngine
import com.zoya.ai.assistant.tasks.workflow.WorkflowParser
import com.zoya.ai.assistant.vision.OcrEngine
import com.zoya.ai.assistant.vision.VisualDetector
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The modular Android Automation Engine. Every command goes through:
 *   1. Security validation (BridgeGuard)
 *   2. Command validation (CommandValidator)
 *   3. Permission checks (PermissionGuard)
 *   4. Execution (dispatcher)
 *   5. Post-execution verification (Verifier)
 *   6. Self-recovery with re-verification
 *   7. Structured result (success/failure/permission/timeout/unsupported/cancelled/blocked)
 */
class AutomationEngine private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        var instance: AutomationEngine? = null
            private set

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = AutomationEngine(context.applicationContext)
                }
            }
        }

        fun get(): AutomationEngine = instance
            ?: throw IllegalStateException("AutomationEngine not initialized. Call init() first.")

        const val DEFAULT_TIMEOUT_MS = 15_000L
    }

    private val cancelled = AtomicBoolean(false)

    /** Currently executing workflow summary, null when idle. */
    @Volatile
    var activeWorkflow: JSONObject? = null
        private set

    val logStore = AutomationLogStore(appContext)

    private val appManager = AppManager(appContext)
    private val settingsLauncher = SettingsLauncher(appContext)
    private val browserAutomator = BrowserAutomator(appContext) { ZoyaAccessibilityService.instance }
    private val formAutomator = FormAutomator { ZoyaAccessibilityService.instance }
    private val gestureStore = GestureStore(appContext)
    private val microphoneController = MicrophoneController(appContext)

    @Volatile
    var cameraController: CameraController? = null
        private set

    val taskScheduler = TaskScheduler(appContext, this::runWorkflowInternal)

    private val selfRecovery = SelfRecovery(
        waitMs = { Thread.sleep(it) },
        pressBack = {
            ZoyaAccessibilityService.instance?.performGlobalActionCompat(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            ) ?: false
        },
        goHome = {
            ZoyaAccessibilityService.instance?.performGlobalActionCompat(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            ) ?: false
        }
    )

    fun setActivity(activity: android.app.Activity?) {
        if (activity != null) {
            cameraController = CameraController(appContext, activity as androidx.lifecycle.LifecycleOwner)
        } else {
            cameraController?.release()
            cameraController = null
        }
    }

    fun cancelCurrentOperation() {
        cancelled.set(true)
    }

    fun resetCancellation() {
        cancelled.set(false)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun screenContext(): ScreenContext? = ZoyaAccessibilityService.instance?.screenContext

    // ------------------------------------------------------------------
    // Main entry point
    // ------------------------------------------------------------------

    fun execute(
        command: String,
        args: Map<String, Any?> = emptyMap(),
        consent: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AutomationResult {
        val startedAt = System.currentTimeMillis()

        // 1. Security validation
        logStore.log("action_start", command, "Executing '$command'")
        BridgeGuard.validateCommand(command, args, consent)?.let { r ->
            logStore.log("failure", command, r.errorMessage, null, "error")
            return r.withDuration(elapsed(startedAt))
        }

        // 2. Command validation
        val validation = CommandValidator.validate(command, args)
        if (!validation.valid) {
            logStore.log("failure", command, validation.result?.errorMessage, null, "error")
            return validation.result!!.withDuration(elapsed(startedAt))
        }

        // 3. Permission checks
        permissionCheckFor(command, args)?.let { r ->
            logStore.log("failure", command, "${r.errorCode}: ${r.errorMessage}", null, "error")
            return r.withDuration(elapsed(startedAt))
        }

        // 4. Execution with retry + self-recovery
        var attempt = 1
        var result = dispatch(command, args, timeoutMs)

        while (!result.ok && attempt <= 3 && shouldAttemptRecovery(result)) {
            logStore.log("retry", command, "Attempt $attempt failed (${result.status}). Trying recovery.")
            val failureKind = selfRecovery.classify(result.status, result.errorCode)
            val outcome = selfRecovery.recover(command, failureKind, screenContext())
            if (!outcome.recovered) {
                if (outcome.requiresUser) {
                    result = AutomationResult(
                        ResultStatus.BLOCKED,
                        data = result.data,
                        errorCode = "NEEDS_USER_INPUT",
                        errorMessage = outcome.reason ?: result.errorMessage,
                        attempt = attempt
                    )
                    logStore.log("recovery", command, "Recovery requires user action: ${outcome.reason}", null, "warn")
                    break
                }
                logStore.log("recovery", command, "Recovery failed: ${outcome.reason ?: result.errorMessage}", null, "warn")
                break
            }
            attempt++
            result = dispatch(command, args, timeoutMs)
        }

        // 5. Verification for important actions
        if (result.ok) {
            val svc = ZoyaAccessibilityService.instance
            val screen = svc?.screenContext
            val verification = Verifier.verify(command, args, result, screen)
            if (!verification.passed) {
                logStore.log("verify", command, "Verification failed: ${verification.detail}", null, "warn")
                // One more recovery + re-dispatch before failing verification.
                val failureKind = selfRecovery.classify(ResultStatus.FAILURE, "VERIFICATION_FAILED")
                val outcome = selfRecovery.recover(command, failureKind, screen)
                if (outcome.recovered) {
                    attempt++
                    result = dispatch(command, args, timeoutMs)
                } else {
                    result = AutomationResult(
                        ResultStatus.FAILURE,
                        data = result.data,
                        errorCode = "VERIFICATION_FAILED",
                        errorMessage = verification.detail ?: "Action could not be verified.",
                        attempt = attempt
                    )
                }
            }
        }

        // 6. Structured result
        if (result.ok) {
            screenContext()?.setLastAction(command)
            screenContext()?.setExpectedResult(null)
        }

        val finalResult = result.copy(
            attempt = attempt,
            durationMs = elapsed(startedAt),
            recovered = attempt > 1
        )
        if (finalResult.ok) {
            logStore.log("complete", command, "'$command' completed in ${finalResult.durationMs}ms (attempt $attempt)")
        } else {
            logStore.log(
                if (finalResult.status == ResultStatus.CANCELLED) "cancel" else "failure",
                command,
                "'$command' ended with ${finalResult.status}: ${finalResult.errorMessage}",
                null,
                if (finalResult.status == ResultStatus.CANCELLED) "warn" else "error"
            )
        }
        return finalResult
    }

    private fun elapsed(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    private fun shouldAttemptRecovery(result: AutomationResult): Boolean {
        return result.status == ResultStatus.FAILURE ||
            result.status == ResultStatus.TIMEOUT ||
            result.status == ResultStatus.BLOCKED
    }

    private fun permissionCheckFor(command: String, args: Map<String, Any?>): AutomationResult? {
        val capability = when (command) {
            "takePhoto", "cameraPermissionStatus" -> "camera"
            "startRecording", "stopRecording", "micPermissionStatus", "microphoneStatus" -> "microphone"
            "findElement", "findElements", "clickElement", "typeText", "longClickElement",
            "getElementInfo", "isElementPresent", "waitForElement", "focusElement", "clearElement",
            "tapCoordinate", "longPressCoordinate", "swipe", "scroll", "gesturePath",
            "readVisibleText", "detectForm", "fillForm", "submitForm", "readScreenText", "getScreenContext" -> {
                // Requires accessibility service, checked separately
                return checkAccessibilityEnabled()
            }
            else -> null
        }
        return capability?.let { PermissionGuard.checkCapability(appContext, it) }
    }

    private fun checkAccessibilityEnabled(): AutomationResult? {
        val enabled = ZoyaAccessibilityService.isEnabled(appContext)
        if (!enabled) {
            return AutomationResult.permissionDenied(
                "ACCESSIBILITY",
                "Accessibility service is not enabled. Open Settings to enable 'Zoya AI Assistant'."
            )
        }
        return null
    }

    // ------------------------------------------------------------------
    // Command dispatcher
    // ------------------------------------------------------------------

    /**
     * Commands that are Zoya's own system/utility functions (logs, permission
     * status, sync, task/workflow management, gesture library management,
     * device status) rather than "automate some app's on-screen UI". These
     * always run through the original engine and are never gated behind a
     * per-app automation file — only foreground-UI interaction commands are.
     */
    private val systemCommands = setOf(
        "getAutomationStatus", "getDeviceCapabilities", "getExecutionLogs",
        "exportLogs", "clearExecutionLogs", "getPermissionStatus",
        "getSecurityStatus", "isBiometricAvailable", "biometricStatus",
        "startAutomation", "stopAutomation", "getActiveWorkflow",
        "getSyncStatus", "setSyncEnabled", "setSyncEndpoint", "syncNow",
        "createTask", "scheduleTask", "listTasks", "updateTask", "deleteTask",
        "cancelTask", "enableTask", "disableTask", "taskHistory", "executeTask",
        "saveWorkflow", "listWorkflows", "getWorkflow", "workflowVersions",
        "restoreWorkflowVersion", "deleteWorkflow",
        "listGestures", "getGesture", "saveGesture", "renameGesture",
        "duplicateGesture", "deleteGesture", "importGesture", "exportGesture",
        "listApps", "currentApp",
        "accessibilityStatus", "screenCaptureStatus", "cameraPermissionStatus",
        "micPermissionStatus", "microphoneStatus", "getScreenContext",
        "recognizeText", "readScreenText", "ocrScreen", "performOCR", "visualDetect",
        "setBrightness", "getBrightness", "setVolume", "getVolume",
        "openAccessibilitySettings", "openBatterySettings",
        "openNotificationSettings", "openAppInfo", "openAppPermissions"
    )

    private fun dispatch(command: String, args: Map<String, Any?>, timeoutMs: Long): AutomationResult {
        if (cancelled.get()) {
            return AutomationResult.cancelled("Operation cancelled.")
        }

        // Zoya's own system/utility commands are never gated behind per-app
        // automation — they don't automate any app's screen.
        if (command in systemCommands) {
            return legacyDispatch(command, args, timeoutMs)
        }

        // Everything else is "automate an app's on-screen UI". This is only
        // allowed via a per-app AppAutomation registered in
        // AutomationRegistry (or the default fallback, once added).
        val currentPackage = ZoyaAccessibilityService.instance?.screenContext?.currentPackage
        val registry = com.zoya.ai.assistant.automation.AutomationRegistry
        val appAutomation = registry.forPackage(currentPackage)?.takeIf { it.handles(command) }
            // "Open this app" style commands run before that app is in the
            // foreground, so the foreground-package lookup above will miss —
            // fall back to scanning every registered automation for a handler.
            ?: registry.anyHandling(command)

        if (appAutomation != null) {
            return appAutomation.execute(this, command, args, timeoutMs)
        }

        return AutomationResult.blocked(
            "NO_AUTOMATION",
            "No automation is configured yet for '${currentPackage ?: "this app"}' (command '$command')."
        )
    }

    @Suppress("unused")
    fun runLegacyDispatch(command: String, args: Map<String, Any?>, timeoutMs: Long): AutomationResult =
        legacyDispatch(command, args, timeoutMs)

    @Suppress("unused")
    private fun legacyDispatch(command: String, args: Map<String, Any?>, timeoutMs: Long): AutomationResult {
        if (cancelled.get()) {
            return AutomationResult.cancelled("Operation cancelled.")
        }
        return when (command) {
            // ---- element discovery & interaction ----
            "findElement" -> findElement(args)
            "findElements" -> findElements(args)
            "getElementInfo" -> findElement(args, returnFirst = true)
            "isElementPresent" -> {
                val found = findElement(args)
                if (found.ok) AutomationResult.success(JSONObject().put("present", true))
                else AutomationResult.success(JSONObject().put("present", false))
            }
            "waitForElement" -> waitForElement(args, timeoutMs)
            "clickElement" -> actOnElement(args) { node ->
                SemanticActions.click(node)
            }
            "longClickElement" -> actOnElement(args) { node ->
                SemanticActions.longClick(node)
            }
            "focusElement" -> actOnElement(args) { node ->
                SemanticActions.focus(node)
            }
            "clearElement" -> actOnElement(args) { node ->
                SemanticActions.clearText(node)
            }
            "typeText", "setText" -> typeText(args)
            "toggleElement" -> actOnElement(args) { node ->
                SemanticActions.toggle(node)
            }
            "setChecked" -> actOnElement(args) { node ->
                SemanticActions.setChecked(node, args["checked"] as? Boolean ?: true)
            }
            "scrollElement" -> actOnElement(args) { node ->
                val dir = args["direction"]?.toString() ?: "forward"
                if (dir == "backward") SemanticActions.scrollBackward(node) else SemanticActions.scrollForward(node)
            }
            "dismissElement" -> actOnElement(args) { node ->
                SemanticActions.dismiss(node)
            }

            // ---- gestures ----
            "tapCoordinate" -> tapCoordinate(args)
            "longPressCoordinate" -> longPressCoordinate(args)
            "swipe", "scroll" -> swipe(args)
            "gesturePath" -> gesturePath(args)
            "pinch", "zoom" -> pinchZoom(args)
            "doubleTap" -> doubleTap(args)

            // ---- gesture recorder ----
            "recordGesture" -> recordGesture(args)
            "stopGestureRecording" -> stopGestureRecording()
            "listGestures" -> listGestures()
            "getGesture" -> getGesture(args)
            "saveGesture" -> saveGesture(args)
            "renameGesture" -> renameGesture(args)
            "duplicateGesture" -> duplicateGesture(args)
            "deleteGesture" -> deleteGesture(args)
            "importGesture" -> importGesture(args)
            "exportGesture" -> exportGesture(args)
            "replayGesture" -> replayGesture(args)

            // ---- apps ----
            "launchApp" -> appManager.launchApp(args["packageName"]?.toString() ?: "")
            "launchAppByName" -> appManager.launchAppByName(args["name"]?.toString() ?: "")
            "listApps" -> appManager.listApps(args["query"]?.toString())
            "currentApp" -> appManager.currentApp()
            "openAppInfo" -> appManager.openAppInfo(args["packageName"]?.toString() ?: "")
            "openAppPermissions" -> appManager.openAppPermissions(args["packageName"]?.toString() ?: "")
            "openNotificationSettings" -> appManager.openNotificationSettings(args["packageName"]?.toString() ?: "")
            "openBatterySettings" -> appManager.openBatterySettings(args["packageName"]?.toString() ?: "")
            "stopApp" -> appManager.stopApp(args["packageName"]?.toString() ?: "")

            // ---- browser ----
            "openUrl" -> browserAutomator.openUrl(args["url"]?.toString() ?: "")
            "searchBrowser" -> browserAutomator.search(args["query"]?.toString() ?: "")
            "searchInBrowser" -> browserAutomator.searchInBrowser(args["query"]?.toString() ?: "")
            "readVisibleText" -> browserAutomator.readVisibleText()
            "clickLink" -> browserAutomator.clickLinkByText(args["partialText"]?.toString() ?: "")
            "browserScroll" -> browserAutomator.scroll(args["direction"]?.toString() ?: "down")
            "verifyNavigation" -> browserAutomator.verifyNavigation(args["url"]?.toString() ?: "")

            // ---- forms ----
            "detectForm" -> formAutomator.detectForm()
            "fillForm" -> formAutomator.fillForm(args["fields"]?.toString()?.let { JSONObject(it) } ?: JSONObject())
            "submitForm" -> formAutomator.submit(args["confirmed"] as? Boolean ?: false)

            // ---- settings ----
            "openSettingsPage" -> settingsLauncher.open(args["page"]?.toString() ?: "")
            "setBrightness" -> settingsLauncher.setBrightness((args["value"] as? Number)?.toInt() ?: -1)
            "getBrightness" -> settingsLauncher.getBrightness()
            "setVolume" -> settingsLauncher.setVolume((args["level"] as? Number)?.toInt() ?: -1)
            "getVolume" -> settingsLauncher.getVolume()

            // ---- accessibility service ----
            "accessibilityStatus" -> accessibilityStatus()
            "globalAction" -> globalAction(args)
            "pressBack" -> globalAction(mapOf("action" to "back"))
            "pressHome" -> globalAction(mapOf("action" to "home"))
            "openAccessibilitySettings" -> {
                ZoyaAccessibilityService.openSettings(appContext)
                AutomationResult.success(JSONObject().put("opened", true))
            }

            // ---- camera / microphone ----
            "takePhoto" -> takePhoto(args)
            "cameraPermissionStatus" -> cameraPermissionStatus()
            "startRecording" -> microphoneController.startRecording(args["fileName"]?.toString())
            "stopRecording" -> microphoneController.stopRecording()
            "micPermissionStatus", "microphoneStatus" -> microphoneController.permissionStatus()

            // ---- vision / OCR ----
            "readScreenText", "ocrScreen", "performOCR" -> ocrScreen()
            "visualDetect" -> visualDetect()
            "screenCaptureStatus" -> AutomationResult.success(
                JSONObject().put("capturing", com.zoya.ai.assistant.vision.ScreenCaptureService.isCapturing())
            )

            // ---- state ----
            "getScreenContext" -> getScreenContext()

            // ---- system status / logs / security ----
            "getAutomationStatus" -> getAutomationStatus()
            "getDeviceCapabilities" -> deviceCapabilities()
            "getExecutionLogs" -> getExecutionLogs(args)
            "exportLogs" -> exportLogs()
            "clearExecutionLogs" -> {
                logStore.clear()
                AutomationResult.success(JSONObject().put("cleared", true))
            }
            "getPermissionStatus" -> permissionStatusCommand()
            "getSecurityStatus" -> securityStatusCommand()
            "isBiometricAvailable", "biometricStatus" -> biometricStatus()
            "startAutomation" -> startAutomationCommand()
            "stopAutomation" -> stopAutomationCommand()
            "getActiveWorkflow" -> activeWorkflowStatus()

            // ---- cloud sync (optional, off by default) ----
            "getSyncStatus" -> getSyncStatusCommand()
            "setSyncEnabled" -> setSyncEnabledCommand(args)
            "setSyncEndpoint" -> setSyncEndpointCommand(args)
            "syncNow" -> syncNowCommand()

            // ---- tasks & workflows ----
            "createTask", "scheduleTask" -> taskScheduler.createTask(args)
            "listTasks" -> taskScheduler.listTasks()
            "updateTask" -> taskScheduler.updateTask(args)
            "deleteTask", "cancelTask" -> taskScheduler.deleteTask(args)
            "enableTask" -> taskScheduler.setEnabled(args, true)
            "disableTask" -> taskScheduler.setEnabled(args, false)
            "taskHistory" -> taskScheduler.taskHistory(args)
            "executeTask" -> taskScheduler.executeNow(args)
            "runWorkflow", "startWorkflow" -> runWorkflowCommand(args)

            // ---- workflow store (versioned) ----
            "saveWorkflow" -> saveWorkflowCommand(args)
            "listWorkflows" -> listWorkflowsCommand()
            "getWorkflow" -> getWorkflowCommand(args)
            "workflowVersions" -> workflowVersionsCommand(args)
            "restoreWorkflowVersion" -> restoreWorkflowVersionCommand(args)
            "deleteWorkflow" -> deleteWorkflowCommand(args)

            else -> AutomationResult.blocked("UNKNOWN_COMMAND", "Unsupported command: '$command'")
        }
    }

    // ------------------------------------------------------------------
    // Element helpers
    // ------------------------------------------------------------------

    private fun buildSelector(args: Map<String, Any?>): Selector {
        return Selector(
            exactText = args["exactText"]?.toString(),
            partialText = args["partialText"]?.toString(),
            regexText = args["regexText"]?.toString(),
            contentDescription = args["contentDescription"]?.toString(),
            contentDescriptionPartial = args["contentDescriptionPartial"]?.toString(),
            resourceId = args["resourceId"]?.toString(),
            className = args["className"]?.toString(),
            packageName = args["packageName"]?.toString(),
            clickable = args["clickable"] as? Boolean,
            enabled = args["enabled"] as? Boolean,
            editable = args["editable"] as? Boolean,
            scrollable = args["scrollable"] as? Boolean,
            index = (args["index"] as? Number)?.toInt() ?: 0
        )
    }

    private fun findElement(args: Map<String, Any?>, returnFirst: Boolean = false): AutomationResult {
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen currently available.")
        val selector = buildSelector(args)
        val nodes = NodeFinder.findAll(root, selector, maxResults = 10)
        root.recycle()
        if (nodes.isEmpty()) {
            return AutomationResult.failure("TARGET_NOT_FOUND", "No element matched the given selector.")
        }
        val target = nodes.getOrNull(selector.index) ?: nodes.first()
        val info = NodeFinder.toNodeInfo(target, "found")
        nodes.forEach { it.recycle() }
        return AutomationResult.success(JSONObject().put("element", info.toJson()))
    }

    private fun findElements(args: Map<String, Any?>): AutomationResult {
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen currently available.")
        val selector = buildSelector(args)
        val nodes = NodeFinder.findAll(root, selector, maxResults = (args["limit"] as? Number)?.toInt() ?: 50)
        root.recycle()
        val arr = JSONArray()
        nodes.forEachIndexed { i, n ->
            arr.put(NodeFinder.toNodeInfo(n, "n$i", includeChildren = false).toJson())
            n.recycle()
        }
        return AutomationResult.success(JSONObject().put("elements", arr).put("count", arr.length()))
    }

    private fun waitForElement(args: Map<String, Any?>, timeoutMs: Long): AutomationResult {
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()
        val selector = buildSelector(args)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get()) return AutomationResult.cancelled("Wait cancelled.")
            val root = svc.screenContext.getRoot()
            if (root != null) {
                val found = NodeFinder.findFirst(root, selector)
                root.recycle()
                if (found != null) {
                    found.recycle()
                    return AutomationResult.success(JSONObject().put("found", true))
                }
            }
            Thread.sleep(250)
        }
        return AutomationResult.timeout("Element did not appear within ${timeoutMs}ms.")
    }

    private fun actOnElement(args: Map<String, Any?>, action: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): AutomationResult {
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen currently available.")
        val selector = buildSelector(args)
        val nodes = NodeFinder.findAll(root, selector, maxResults = 10)
        root.recycle()
        if (nodes.isEmpty()) {
            return AutomationResult.failure("TARGET_NOT_FOUND", "No element matched the given selector.")
        }
        val target = nodes.getOrNull(selector.index) ?: nodes.first()
        val ok = action(target)
        val description = target.text?.toString() ?: target.contentDescription?.toString() ?: target.viewIdResourceName
        target.recycle()
        nodes.forEach { it.recycle() }
        return if (ok) {
            AutomationResult.success(JSONObject().put("target", description ?: JSONObject.NULL).put("action", "executed"))
        } else {
            AutomationResult.failure("ACTION_REJECTED", "The accessibility action was rejected for the matched element.")
        }
    }

    private fun typeText(args: Map<String, Any?>): AutomationResult {
        val text = args["text"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "No text provided.")
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()

        // Determine target: focused field or selector.
        var target: android.view.accessibility.AccessibilityNodeInfo? = null
        if (args.containsKey("exactText") || args.containsKey("partialText") || args.containsKey("resourceId")) {
            val root = svc.screenContext.getRoot()
            if (root != null) {
                target = NodeFinder.findFirst(root, buildSelector(args))
                root.recycle()
            }
        }
        if (target == null) {
            val root = svc.screenContext.getRoot()
            if (root != null) {
                target = NodeFinder.walk(root) { node, _ ->
                    if (node.isFocused && node.isEditable) node else null
                }
                root.recycle()
            }
        }
        if (target == null) {
            return AutomationResult.failure("TARGET_NOT_FOUND", "No focused or matching editable field found.")
        }
        val ok = SemanticActions.focus(target) && SemanticActions.setText(target, text)
        target.recycle()
        if (!ok) {
            return AutomationResult.failure("TYPE_FAILED", "Could not insert text into the field.")
        }
        // Never echo sensitive values back.
        val isSensitive = SecretsRedactor.isSensitiveField("text")
        return AutomationResult.success(
            JSONObject().put("length", text.length).put("echoed", !isSensitive)
        )
    }

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    private fun gestureInjector(): GestureInjector? {
        val svc = ZoyaAccessibilityService.instance ?: return null
        return svc.gestureInjector
    }

    private fun buildGestureSpec(args: Map<String, Any?>): GestureInjector.GestureSpec? {
        val type = args["type"]?.toString() ?: args["gesture"]?.toString() ?: "swipe"
        val normalized = args["normalized"] as? Boolean ?: true
        return GestureInjector.GestureSpec(
            type = type,
            fromX = (args["fromX"] as? Number)?.toDouble(),
            fromY = (args["fromY"] as? Number)?.toDouble(),
            toX = (args["toX"] as? Number)?.toDouble(),
            toY = (args["toY"] as? Number)?.toDouble(),
            x = (args["x"] as? Number)?.toDouble(),
            y = (args["y"] as? Number)?.toDouble(),
            durationMs = (args["durationMs"] as? Number)?.toLong() ?: 300,
            delayMs = (args["delayMs"] as? Number)?.toLong() ?: 0,
            points = parsePoints(args["points"]),
            repeatCount = (args["repeatCount"] as? Number)?.toInt() ?: 1,
            normalized = normalized,
            scaleFactor = (args["scaleFactor"] as? Number)?.toDouble()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePoints(raw: Any?): List<List<Double>>? {
        if (raw !is List<*>) return null
        return raw.mapNotNull { item ->
            when (item) {
                is List<*> -> {
                    val x = (item.getOrNull(0) as? Number)?.toDouble()
                    val y = (item.getOrNull(1) as? Number)?.toDouble()
                    if (x != null && y != null) listOf(x, y) else null
                }
                is JSONArray -> {
                    val x = item.optDouble(0, Double.NaN)
                    val y = item.optDouble(1, Double.NaN)
                    if (x.isNaN() || y.isNaN()) null else listOf(x, y)
                }
                else -> null
            }
        }
    }

    private fun tapCoordinate(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val x = (args["x"] as? Number)?.toDouble()
        val y = (args["y"] as? Number)?.toDouble()
        if (x == null || y == null) return AutomationResult.blocked("MISSING_ARGUMENT", "x and y are required.")
        val spec = GestureInjector.GestureSpec(
            "tap", x = x, y = y,
            durationMs = (args["durationMs"] as? Number)?.toLong() ?: 100,
            normalized = args["normalized"] as? Boolean ?: true
        )
        return injector.dispatch(spec)
    }

    private fun longPressCoordinate(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val x = (args["x"] as? Number)?.toDouble()
        val y = (args["y"] as? Number)?.toDouble()
        if (x == null || y == null) return AutomationResult.blocked("MISSING_ARGUMENT", "x and y are required.")
        val spec = GestureInjector.GestureSpec(
            "longpress", x = x, y = y,
            durationMs = (args["durationMs"] as? Number)?.toLong() ?: 800,
            normalized = args["normalized"] as? Boolean ?: true
        )
        return injector.dispatch(spec)
    }

    private fun swipe(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val spec = buildGestureSpec(args)
            ?: return AutomationResult.blocked("INVALID_GESTURE", "Invalid swipe parameters.")
        return injector.dispatch(spec)
    }

    private fun gesturePath(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val spec = GestureInjector.GestureSpec(
            type = "path",
            points = parsePoints(args["points"]),
            durationMs = (args["durationMs"] as? Number)?.toLong() ?: 800,
            normalized = args["normalized"] as? Boolean ?: true
        )
        return injector.dispatch(spec)
    }

    private fun pinchZoom(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val spec = GestureInjector.GestureSpec(
            type = args["type"]?.toString() ?: "pinch",
            x = (args["x"] as? Number)?.toDouble() ?: 0.5,
            y = (args["y"] as? Number)?.toDouble() ?: 0.5,
            durationMs = (args["durationMs"] as? Number)?.toLong() ?: 400,
            scaleFactor = (args["scaleFactor"] as? Number)?.toDouble() ?: 0.3,
            normalized = args["normalized"] as? Boolean ?: true
        )
        return injector.dispatch(spec)
    }

    private fun doubleTap(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val x = (args["x"] as? Number)?.toDouble()
        val y = (args["y"] as? Number)?.toDouble()
        if (x == null || y == null) return AutomationResult.blocked("MISSING_ARGUMENT", "x and y are required.")
        val spec = GestureInjector.GestureSpec(
            "doubletap", x = x, y = y,
            durationMs = (args["durationMs"] as? Number)?.toLong() ?: 80,
            normalized = args["normalized"] as? Boolean ?: true
        )
        return injector.dispatch(spec)
    }

    // ------------------------------------------------------------------
    // Gesture recorder
    // ------------------------------------------------------------------

    private fun recordGesture(args: Map<String, Any?>): AutomationResult {
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()
        if (!android.provider.Settings.canDrawOverlays(appContext)) {
            return AutomationResult.permissionDenied(
                "SYSTEM_ALERT_WINDOW",
                "Gesture recording needs 'Display over other apps' permission for the recording overlay."
            )
        }
        if (svc.gestureRecorder != null) {
            return AutomationResult.failure("ALREADY_RECORDING", "A gesture recording is already in progress.")
        }
        val durationMs = (args["durationMs"] as? Number)?.toLong() ?: 10_000
        startGestureRecordingInternal(svc, durationMs)
        return AutomationResult.success(JSONObject().put("recording", true).put("durationMs", durationMs))
    }

    private fun startGestureRecordingInternal(svc: ZoyaAccessibilityService, durationMs: Long) {
        val screen = svc.screenContext
        val rec = GestureRecorder()
        svc.registerGestureRecorder(rec)
        val store = gestureStore
        rec.startRecording(
            appContext,
            durationMs,
            screen.currentPackage,
            screen.screenWidth,
            screen.screenHeight,
            screen.orientationDegrees
        ) { gesture ->
            if (gesture != null) {
                store.save(gesture)
            }
            svc.unregisterGestureRecorder()
        }
    }

    private fun stopGestureRecording(): AutomationResult {
        val svc = ZoyaAccessibilityService.instance ?: return accessibilityDisabled()
        val rec = svc.gestureRecorder
        if (rec == null) return AutomationResult.failure("NOT_RECORDING", "No active gesture recording.")
        val gesture = rec.stopRecording()
        svc.unregisterGestureRecorder()
        if (gesture != null) {
            gestureStore.save(gesture)
            return AutomationResult.success(JSONObject().put("gesture", gesture.toJson()))
        }
        return AutomationResult.failure("NO_GESTURE", "No gesture path was recorded.")
    }

    private fun listGestures(): AutomationResult {
        val arr = JSONArray()
        gestureStore.list().forEach { arr.put(it.toJson()) }
        return AutomationResult.success(JSONObject().put("gestures", arr).put("count", arr.length()))
    }

    private fun getGesture(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "gesture id required.")
        val g = gestureStore.get(id) ?: return AutomationResult.failure("NOT_FOUND", "Gesture '$id' not found.")
        return AutomationResult.success(JSONObject().put("gesture", g.toJson()))
    }

    private fun saveGesture(args: Map<String, Any?>): AutomationResult {
        val raw = args["gesture"]?.toString()
            ?: return AutomationResult.blocked("MISSING_ARGUMENT", "gesture JSON required.")
        val g = runCatching { gestureStore.importJson(raw) }.getOrNull()
            ?: return AutomationResult.failure("INVALID_GESTURE", "Could not parse gesture JSON.")
        return AutomationResult.success(JSONObject().put("gesture", g.toJson()))
    }

    private fun renameGesture(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "gesture id required.")
        val name = args["name"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "new name required.")
        if (gestureStore.rename(id, name)) {
            return AutomationResult.success(JSONObject().put("renamed", true))
        }
        return AutomationResult.failure("RENAME_FAILED", "Could not rename gesture '$id'.")
    }

    private fun duplicateGesture(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "gesture id required.")
        val name = args["name"]?.toString()
        val copy = gestureStore.duplicate(id, name)
            ?: return AutomationResult.failure("DUPLICATE_FAILED", "Could not duplicate gesture '$id'.")
        return AutomationResult.success(JSONObject().put("gesture", copy.toJson()))
    }

    private fun deleteGesture(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "gesture id required.")
        if (gestureStore.delete(id)) {
            return AutomationResult.success(JSONObject().put("deleted", true))
        }
        return AutomationResult.failure("DELETE_FAILED", "Could not delete gesture '$id'.")
    }

    private fun importGesture(args: Map<String, Any?>): AutomationResult {
        val raw = args["json"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "json required.")
        val imported = gestureStore.importBundle(raw)
        return AutomationResult.success(JSONObject().put("imported", imported.size))
    }

    private fun exportGesture(args: Map<String, Any?>): AutomationResult {
        val ids = args["ids"] as? List<*> ?: emptyList<Any>()
        val idList = ids.mapNotNull { it?.toString() }
        val bundle = gestureStore.exportBundle(idList)
        return AutomationResult.success(JSONObject().put("json", bundle).put("count", idList.size))
    }

    private fun replayGesture(args: Map<String, Any?>): AutomationResult {
        val injector = gestureInjector() ?: return accessibilityDisabled()
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "gesture id required.")
        val gesture = gestureStore.get(id) ?: return AutomationResult.failure("NOT_FOUND", "Gesture '$id' not found.")

        // Semantic-first replay: if the recorded UI context text is present,
        // prefer semantic selectors over raw coordinates (handled by engine
        // callers). Here we replay the recorded path, scaled to the current
        // screen, honoring the recorded screen size.
        val screen = screenContext()
        val currentW = screen?.screenWidth ?: gesture.screenWidth
        val currentH = screen?.screenHeight ?: gesture.screenHeight
        if (currentW <= 0 || currentH <= 0) {
            return AutomationResult.failure("NO_SCREEN", "Screen size unavailable for replay.")
        }

        val normalized = gesture.points.map { p ->
            val nx = if (gesture.screenWidth > 0) p[0] * gesture.screenWidth / currentW else p[0]
            val ny = if (gesture.screenHeight > 0) p[1] * gesture.screenHeight / currentH else p[1]
            listOf(nx, ny)
        }

        val spec = GestureInjector.GestureSpec(
            type = "path",
            points = normalized,
            durationMs = gesture.durationMs.coerceAtLeast(200),
            normalized = true
        )
        val result = injector.dispatch(spec)
        return if (result.ok) {
            AutomationResult.success(JSONObject().put("gestureId", id).put("replayed", true))
        } else {
            result
        }
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun takePhoto(args: Map<String, Any?>): AutomationResult {
        val camera = args["camera"]?.toString() ?: "back"
        val controller = cameraController
            ?: return AutomationResult.failure("CAMERA_UNAVAILABLE", "Camera controller not initialized (activity required).")
        val permission = PermissionGuard.checkCapability(appContext, "camera")
        if (permission != null) return permission
        return runBlocking {
            val future = controller.takePhoto(camera, args["fileName"]?.toString())
            try {
                kotlinx.coroutines.withTimeoutOrNull(20_000) { future.await() }
                    ?: AutomationResult.timeout("Photo capture timed out.")
            } catch (e: Exception) {
                AutomationResult.failure("CAMERA_ERROR", "Camera error: ${e.message}")
            }
        }
    }

    private fun cameraPermissionStatus(): AutomationResult {
        val controller = cameraController
        return if (controller != null) controller.permissionStatus()
        else AutomationResult.success(JSONObject().put("granted", false).put("permission", "CAMERA").put("controllerReady", false))
    }

    // ------------------------------------------------------------------
    // Vision
    // ------------------------------------------------------------------

    private fun ocrScreen(): AutomationResult {
        // Accessibility text first (highest fidelity, fastest).
        val svc = ZoyaAccessibilityService.instance
        val root = svc?.screenContext?.getRoot()
        if (root != null) {
            val texts = mutableListOf<String>()
            NodeFinder.walk(root) { node, _ ->
                val text = node.text?.toString()
                if (!text.isNullOrBlank() && node.isVisibleToUser) {
                    texts.add(text)
                }
                null
            }
            root.recycle()
            if (texts.isNotEmpty()) {
                val data = JSONObject()
                data.put("text", texts.joinToString("\n"))
                data.put("source", "accessibility")
                svc.screenContext.setOcrText(texts.joinToString("\n"))
                return AutomationResult.success(data)
            }
        }

        // Accessibility tree had nothing usable (custom-drawn UI, video,
        // WebView canvas content, etc). Fall back to real bitmap OCR via
        // ML Kit, which requires the screen-capture (MediaProjection)
        // service to already be running.
        if (!com.zoya.ai.assistant.vision.ScreenCaptureService.isCapturing()) {
            return AutomationResult.blocked(
                "CAPTURE_NOT_STARTED",
                "No text found via accessibility, and screen capture isn't running for OCR fallback. Start screen capture first."
            )
        }

        val ocrResult = OcrEngine.awaitSingleFrame()
        if (ocrResult.ok) {
            val text = ocrResult.data?.optString("text").orEmpty()
            if (text.isNotBlank()) {
                svc?.screenContext?.setOcrText(text)
                ocrResult.data?.put("source", "ocr")
                return ocrResult
            }
            return AutomationResult.failure("NO_TEXT", "No visible text found on screen.")
        }
        return ocrResult
    }

    private fun visualDetect(): AutomationResult {
        val ocr = ocrScreen()
        val svc = ZoyaAccessibilityService.instance
        val screen = svc?.screenContext
        val result = VisualDetector.detect(ocr, null, screen?.screenWidth ?: 0, screen?.screenHeight ?: 0)
        return if (result.ok) result else ocr
    }

    private fun getScreenContext(): AutomationResult {
        val svc = ZoyaAccessibilityService.instance
        if (svc == null) {
            val data = JSONObject()
            data.put("accessibilityEnabled", false)
            data.put("serviceAvailable", false)
            return AutomationResult.success(data)
        }
        val data = svc.screenContext.toJson()
        data.put("accessibilityEnabled", true)
        data.put("serviceAvailable", true)
        return AutomationResult.success(data)
    }

    private fun accessibilityStatus(): AutomationResult {
        val enabled = ZoyaAccessibilityService.isEnabled(appContext)
        val info = ZoyaAccessibilityService.instance?.serviceInfo
        val gestureCapability = info != null &&
            info.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        val data = JSONObject()
        data.put("enabled", enabled)
        data.put("serviceConnected", ZoyaAccessibilityService.instance != null)
        data.put("canPerformGestures", gestureCapability)
        data.put("overlayGranted", android.provider.Settings.canDrawOverlays(appContext))
        data.put("writeSettingsGranted", android.provider.Settings.System.canWrite(appContext))
        data.put("notificationPolicyGranted", PermissionGuard.isNotificationPolicyAccessGranted(appContext))
        return AutomationResult.success(data)
    }

    /**
     * Performs a global accessibility action (back / home / recents /
     * notifications / screenshot). Requires the accessibility service to be
     * connected; returns UNSUPPORTED when the action is unavailable.
     */
    private fun globalAction(args: Map<String, Any?>): AutomationResult {
        val svc = ZoyaAccessibilityService.instance
            ?: return AutomationResult.failure("ACCESSIBILITY_OFF", "Accessibility service is not connected.")
        val action = args["action"]?.toString()?.lowercase() ?: ""
        val global = when (action) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "screenshot" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return AutomationResult.blocked("INVALID_ACTION", "Unknown global action: '$action'")
        }
        val performed = try {
            svc.performGlobalActionCompat(global)
        } catch (e: Exception) {
            return AutomationResult.failure("ACTION_FAILED", "Could not perform '$action': ${e.message}")
        }
        if (!performed) {
            return AutomationResult.failure("ACTION_UNSUPPORTED", "System rejected global action '$action'.")
        }
        return AutomationResult.success(JSONObject().put("action", action).put("performed", true))
    }

    /**
     * Comprehensive automation status for the dashboard: service flags, active
     * workflow, scheduled tasks, recent executions and recent errors.
     */
    private fun getAutomationStatus(): AutomationResult {
        val status = JSONObject()
        status.put("accessibilityEnabled", ZoyaAccessibilityService.isEnabled(appContext))
        status.put("accessibilityConnected", ZoyaAccessibilityService.instance != null)
        status.put("overlayGranted", android.provider.Settings.canDrawOverlays(appContext))
        status.put("writeSettingsGranted", android.provider.Settings.System.canWrite(appContext))
        status.put("screenCaptureActive", com.zoya.ai.assistant.vision.ScreenCaptureService.isCapturing())
        status.put("cameraGranted", PermissionGuard.isRuntimePermissionGranted(appContext, android.Manifest.permission.CAMERA))
        status.put("microphoneGranted", PermissionGuard.isRuntimePermissionGranted(appContext, android.Manifest.permission.RECORD_AUDIO))
        status.put("notificationPolicyGranted", PermissionGuard.isNotificationPolicyAccessGranted(appContext))
        status.put("foregroundAutomationActive", com.zoya.ai.assistant.tasks.AutomationForegroundService.isRunning)
        status.put("activeWorkflow", activeWorkflow ?: JSONObject.NULL)

        val tasks = JSONArray()
        val taskStore = TaskStore(appContext)
        taskStore.allTasks().forEach { tasks.put(it.toJson()) }
        status.put("scheduledTasks", tasks)
        status.put("scheduledTaskCount", tasks.length())

        val runs = JSONArray()
        taskStore.allHistory().take(20).forEach { runs.put(it.toJson()) }
        status.put("recentExecutions", runs)

        val errors = JSONArray()
        logStore.recent(100).filter { it.optString("level") == "error" }.forEach { errors.put(it) }
        status.put("recentErrors", errors)

        status.put("recentLogs", JSONArray().apply {
            logStore.recent(30).forEach { put(it) }
        })

        status.put("deviceCapabilities", deviceCapabilities().data ?: JSONObject())
        return AutomationResult.success(status)
    }

    /** Reports device + platform capabilities so the UI can disable unsupported features. */
    private fun deviceCapabilities(): AutomationResult {
        val caps = JSONObject()
        caps.put("sdkInt", android.os.Build.VERSION.SDK_INT)
        caps.put("androidVersion", android.os.Build.VERSION.RELEASE)
        caps.put("manufacturer", android.os.Build.MANUFACTURER)
        caps.put("model", android.os.Build.MODEL)
        caps.put("hasCamera", appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))
        caps.put("hasFrontCamera", appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT))
        caps.put("hasMicrophone", appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE))
        caps.put("hasBiometric", com.zoya.ai.assistant.core.security.SecureStorage(appContext).canUseBiometric(appContext))
        caps.put("supportsAccessibilityGestures", ZoyaAccessibilityService.instance?.serviceInfo?.let {
            it.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        } ?: false)
        caps.put("supportsMediaProjection", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
        caps.put("supportsOcr", true) // bundled ML Kit, offline
        caps.put("supportsVisionDetection", true)
        caps.put("supportsForegroundService", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
        caps.put("supportsPictureInPicture", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        caps.put("supportsAdaptiveIcons", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        caps.put(
            "gestureNavigation",
            runCatching {
                // 2 = gesture navigation, 0 = 3-button (system setting).
                android.provider.Settings.Secure.getString(
                    appContext.contentResolver,
                    "navigation_mode"
                ) == "2"
            }.getOrDefault(false)
        )
        // Advanced privilege detection (Shizuku / device owner) — capability only.
        caps.put("shizukuAvailable", com.zoya.ai.assistant.privilege.AdvancedPrivileges.isShizukuInstalled(appContext))
        caps.put("deviceOwnerActive", com.zoya.ai.assistant.privilege.AdvancedPrivileges.isDeviceOwner(appContext))
        return AutomationResult.success(caps)
    }

    private fun getExecutionLogs(args: Map<String, Any?>): AutomationResult {
        val limit = ((args["limit"] as? Number)?.toInt() ?: 200).coerceIn(1, 1000)
        return AutomationResult.success(
            JSONObject().put("logs", JSONArray().apply {
                logStore.recent(limit).forEach { put(it) }
            }).put("count", logStore.recent(limit).size)
        )
    }

    /** Writes the automation log to a shareable TXT file and returns its path. */
    fun exportLogs(): AutomationResult {
        return try {
            val logs = logStore.recent(500)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val sb = StringBuilder()
            sb.appendLine("Zoya AI Assistant - Automation Log Export")
            sb.appendLine("Generated: ${fmt.format(java.util.Date())}")
            sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            sb.appendLine("App: ${appContext.packageName}")
            sb.appendLine("Accessibility: ${ZoyaAccessibilityService.isEnabled(appContext)}")
            sb.appendLine("Overlay: ${android.provider.Settings.canDrawOverlays(appContext)}")
            sb.appendLine("================================================================")
            sb.appendLine()
            if (logs.isEmpty()) {
                sb.appendLine("(no automation log entries yet)")
            } else {
                val tf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                for (e in logs) {
                    val ts = e.optLong("ts", 0L)
                    val time = if (ts > 0) tf.format(java.util.Date(ts)) else "--"
                    val cmd = e.optString("command", "")
                    val detail = e.optString("detail", "")
                    sb.appendLine("[$time] [${e.optString("level")}] [${e.optString("phase")}] $cmd $detail".trimEnd())
                    e.optJSONObject("data")?.let { sb.appendLine("      data: ${it.toString()}") }
                }
            }
            val dir = java.io.File(appContext.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val file = java.io.File(dir, "zoya_logs_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            AutomationResult.success(
                JSONObject().apply {
                    put("path", file.absolutePath)
                    put("size", file.length())
                    put("entries", logs.size)
                }
            )
        } catch (e: Exception) {
            AutomationResult.failure("EXPORT_FAILED", "Failed to export logs: ${e.message}")
        }
    }

    private fun permissionStatusCommand(): AutomationResult {
        val data = JSONObject()
        data.put("camera", PermissionGuard.isRuntimePermissionGranted(appContext, android.Manifest.permission.CAMERA))
        data.put("microphone", PermissionGuard.isRuntimePermissionGranted(appContext, android.Manifest.permission.RECORD_AUDIO))
        data.put("accessibility", ZoyaAccessibilityService.isEnabled(appContext))
        data.put("overlay", android.provider.Settings.canDrawOverlays(appContext))
        data.put("write_settings", android.provider.Settings.System.canWrite(appContext))
        data.put("notifications", PermissionGuard.isRuntimePermissionGranted(appContext, android.Manifest.permission.POST_NOTIFICATIONS))
        data.put("ignoreBatteryOptimizations", PermissionGuard.isIgnoringBatteryOptimizations(appContext))
        return AutomationResult.success(data)
    }

    private fun securityStatusCommand(): AutomationResult {
        val secure = com.zoya.ai.assistant.core.security.SecureStorage(appContext)
        val data = JSONObject()
        data.put("biometricAvailable", secure.canUseBiometric(appContext))
        data.put("secureStorageEnabled", true)
        data.put("deviceLockEnabled", com.zoya.ai.assistant.privilege.AdvancedPrivileges.isDeviceLocked(appContext))
        data.put("screenCaptureActive", com.zoya.ai.assistant.vision.ScreenCaptureService.isCapturing())
        return AutomationResult.success(data)
    }

    private fun biometricStatus(): AutomationResult {
        val secure = com.zoya.ai.assistant.core.security.SecureStorage(appContext)
        return AutomationResult.success(
            JSONObject().put("available", secure.canUseBiometric(appContext))
        )
    }

    private fun startAutomationCommand(): AutomationResult {
        com.zoya.ai.assistant.tasks.AutomationForegroundService.start(appContext)
        return AutomationResult.success(JSONObject().put("started", true))
    }

    private fun stopAutomationCommand(): AutomationResult {
        cancelCurrentOperation()
        com.zoya.ai.assistant.tasks.AutomationForegroundService.stop(appContext)
        return AutomationResult.success(JSONObject().put("stopped", true))
    }

    private fun activeWorkflowStatus(): AutomationResult {
        return AutomationResult.success(
            JSONObject().put("activeWorkflow", activeWorkflow ?: JSONObject.NULL)
        )
    }

    // ------------------------------------------------------------------
    // Versioned workflow store
    // ------------------------------------------------------------------

    private fun saveWorkflowCommand(args: Map<String, Any?>): AutomationResult {
        val raw = args["workflow"]?.toString()
            ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow definition required.")
        val store = com.zoya.ai.assistant.tasks.workflow.WorkflowStore(appContext)
        val version = store.save(raw)
        val id = runCatching { com.zoya.ai.assistant.tasks.workflow.WorkflowParser.parse(raw).id }
            .getOrElse { "unknown" }
        return AutomationResult.success(
            JSONObject().put("version", version).put("workflowId", id)
        )
    }

    private fun listWorkflowsCommand(): AutomationResult {
        val store = com.zoya.ai.assistant.tasks.workflow.WorkflowStore(appContext)
        return AutomationResult.success(
            JSONObject().put("workflows", JSONArray().apply {
                store.allWorkflows().forEach { put(it.toJson()) }
            }).put("count", store.allWorkflows().size)
        )
    }

    private fun getWorkflowCommand(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow id required.")
        val store = com.zoya.ai.assistant.tasks.workflow.WorkflowStore(appContext)
        val raw = store.latestRaw(id) ?: return AutomationResult.failure("NOT_FOUND", "Workflow '$id' not found.")
        return AutomationResult.success(JSONObject().put("workflow", raw))
    }

    private fun workflowVersionsCommand(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow id required.")
        val store = com.zoya.ai.assistant.tasks.workflow.WorkflowStore(appContext)
        return AutomationResult.success(
            JSONObject().put("versions", JSONArray().apply {
                store.allVersions(id).forEach { put(it) }
            })
        )
    }

    private fun restoreWorkflowVersionCommand(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow id required.")
        val version = (args["version"] as? Number)?.toInt() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "version required.")
        val store = com.zoya.ai.assistant.tasks.workflow.WorkflowStore(appContext)
        val ok = store.restoreVersion(id, version)
        return if (ok) AutomationResult.success(JSONObject().put("restored", true).put("version", version))
        else AutomationResult.failure("NOT_FOUND", "Version $version not found for workflow '$id'.")
    }

    private fun deleteWorkflowCommand(args: Map<String, Any?>): AutomationResult {
        val id = args["id"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow id required.")
        val store = com.zoya.ai.assistant.tasks.workflow.WorkflowStore(appContext)
        store.delete(id)
        return AutomationResult.success(JSONObject().put("deleted", true))
    }

    // ------------------------------------------------------------------
    // Cloud sync
    // ------------------------------------------------------------------

    private fun getSyncStatusCommand(): AutomationResult {
        return AutomationResult.success(com.zoya.ai.assistant.core.sync.CloudSyncManager(appContext).statusJson())
    }

    private fun setSyncEnabledCommand(args: Map<String, Any?>): AutomationResult {
        val enabled = args["enabled"] as? Boolean ?: return AutomationResult.blocked("MISSING_ARGUMENT", "enabled boolean required.")
        val manager = com.zoya.ai.assistant.core.sync.CloudSyncManager(appContext)
        if (enabled && manager.getEndpoint().isBlank()) {
            return AutomationResult.blocked("ENDPOINT_REQUIRED", "Set an HTTPS sync endpoint before enabling cloud sync.")
        }
        manager.setEnabled(enabled)
        logStore.log("info", "setSyncEnabled", "Cloud sync ${if (enabled) "enabled" else "disabled"}")
        return AutomationResult.success(manager.statusJson())
    }

    private fun setSyncEndpointCommand(args: Map<String, Any?>): AutomationResult {
        val endpoint = args["endpoint"]?.toString() ?: return AutomationResult.blocked("MISSING_ARGUMENT", "endpoint required.")
        if (!endpoint.startsWith("https://")) {
            return AutomationResult.blocked("INSECURE_ENDPOINT", "Only HTTPS sync endpoints are allowed.")
        }
        val manager = com.zoya.ai.assistant.core.sync.CloudSyncManager(appContext)
        manager.setEndpoint(endpoint)
        return AutomationResult.success(manager.statusJson())
    }

    private fun syncNowCommand(): AutomationResult {
        val result = com.zoya.ai.assistant.core.sync.CloudSyncManager(appContext).syncNow()
        if (result.success) {
            return AutomationResult.success(com.zoya.ai.assistant.core.sync.CloudSyncManager(appContext).statusJson())
        }
        return AutomationResult.failure(result.code ?: "SYNC_FAILED", result.message ?: "Sync failed.")
    }

    // ------------------------------------------------------------------
    // Tasks & workflows
    // ------------------------------------------------------------------

    private fun runWorkflowCommand(args: Map<String, Any?>): AutomationResult {
        val workflowRaw = args["workflow"]?.toString()
            ?: return AutomationResult.blocked("MISSING_ARGUMENT", "workflow definition required.")
        return runWorkflowInternal(workflowRaw, reportToStore = false)
    }

    fun runWorkflowInternal(task: com.zoya.ai.assistant.tasks.Task): AutomationResult {
        return runWorkflowInternal(task.workflow, reportToStore = true, taskId = task.id)
    }

    fun runWorkflowInternal(workflowRaw: String, reportToStore: Boolean = false, taskId: String? = null): AutomationResult {
        val workflow = try {
            WorkflowParser.parse(workflowRaw)
        } catch (e: Exception) {
            return AutomationResult.failure("INVALID_WORKFLOW", "Invalid workflow: ${e.message}")
        }

        val runtime = object : com.zoya.ai.assistant.tasks.workflow.WorkflowRuntime {
            override fun executeAction(command: String, args: JSONObject): AutomationResult {
                val map = HashMap<String, Any?>()
                args.keys().forEach { key -> map[key] = args.opt(key) }
                return execute(command, map, consent = true, timeoutMs = DEFAULT_TIMEOUT_MS)
            }

            override fun evaluateCondition(condition: com.zoya.ai.assistant.tasks.workflow.Condition): Boolean =
                evaluateWorkflowCondition(condition)

            override fun verifyAction(command: String, args: JSONObject, result: AutomationResult): Boolean {
                val map = HashMap<String, Any?>()
                args.keys().forEach { key -> map[key] = args.opt(key) }
                val verification = Verifier.verify(command, map, result, screenContext())
                return verification.passed
            }

            override fun screenContext(): ScreenContext? = this@AutomationEngine.screenContext()

            override fun isCancelled(): Boolean = cancelled.get()

            override fun sleep(ms: Long): Boolean {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < ms) {
                    if (cancelled.get()) return false
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        return false
                    }
                }
                return true
            }
        }

        val engine = WorkflowEngine(runtime)
        resetCancellation()
        val workflowMeta = JSONObject().apply {
            put("id", workflow.id)
            put("taskId", taskId ?: JSONObject.NULL)
            put("startedAt", System.currentTimeMillis())
            put("stepCount", workflow.steps.size)
        }
        activeWorkflow = workflowMeta
        logStore.log("task_start", "runWorkflow", "Workflow '${workflow.id}' started (${workflow.steps.size} steps)")
        com.zoya.ai.assistant.tasks.AutomationForegroundService.start(appContext)
        val runResult = engine.run(workflow)
        activeWorkflow = null
        com.zoya.ai.assistant.tasks.AutomationForegroundService.stop(appContext)
        logStore.log(
            if (runResult.success) "complete" else "failure",
            "runWorkflow",
            "Workflow '${workflow.id}' ${if (runResult.success) "completed" else "failed"}: ${runResult.errorMessage ?: runResult.status}",
            level = if (runResult.success) "info" else "error"
        )

        if (reportToStore && taskId != null) {
            taskScheduler.recordRun(
                taskId = taskId,
                success = runResult.success,
                status = runResult.status.name,
                errorMessage = runResult.errorMessage,
                durationMs = runResult.durationMs,
                stepSummary = runResult.stepHistory.takeLast(5).joinToString("; ") { "${it.command ?: it.type}:${if (it.success) "ok" else "fail"}" }
            )
        }

        val data = JSONObject()
        data.put("workflowId", workflow.id)
        data.put("success", runResult.success)
        data.put("status", runResult.status.name)
        data.put("errorMessage", runResult.errorMessage ?: JSONObject.NULL)
        data.put("stepCount", runResult.stepHistory.size)
        data.put("durationMs", runResult.durationMs)
        data.put("stepHistory", runResult.stepHistory.take(50).map { rec ->
            JSONObject().apply {
                put("index", rec.index)
                put("type", rec.type)
                put("command", rec.command ?: JSONObject.NULL)
                put("success", rec.success)
                put("durationMs", rec.durationMs)
                put("errorMessage", rec.errorMessage ?: JSONObject.NULL)
            }
        })
        data.put("variables", JSONObject(runResult.variables))

        return AutomationResult(
            status = if (runResult.success) ResultStatus.SUCCESS else runResult.status,
            data = data,
            errorCode = if (runResult.success) null else runResult.status.name,
            errorMessage = if (runResult.success) null else (runResult.errorMessage ?: "Workflow failed.")
        )
    }

    private fun evaluateWorkflowCondition(condition: com.zoya.ai.assistant.tasks.workflow.Condition): Boolean {
        val svc = ZoyaAccessibilityService.instance
        return when (condition) {
            is com.zoya.ai.assistant.tasks.workflow.Condition.PackageIs -> {
                svc?.screenContext?.currentPackage == condition.packageName
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.TextVisible -> {
                val root = svc?.screenContext?.getRoot() ?: return false
                val found = NodeFinder.walk(root) { node, _ ->
                    val text = node.text?.toString() ?: node.contentDescription?.toString()
                    if (text != null) {
                        if (condition.partial) text.contains(condition.text, ignoreCase = true)
                        else text.equals(condition.text, ignoreCase = true)
                    } else false
                } ?: false
                root.recycle()
                found
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.OcrContains -> {
                val screenText = svc?.screenContext?.lastOcrText ?: ocrScreen().data?.optString("text", "")
                screenText?.contains(condition.text, ignoreCase = true) == true
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.AccessibilityNode -> {
                val root = svc?.screenContext?.getRoot() ?: return false
                val found = NodeFinder.findFirst(root, condition.selector) != null
                root.recycle()
                found
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.ScreenState -> {
                svc?.screenContext?.workflowState == condition.state
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.Variable -> {
                // Variables are handled by the WorkflowEngine; here fall back false.
                false
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.And -> {
                condition.conditions.all { evaluateWorkflowCondition(it) }
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.Or -> {
                condition.conditions.any { evaluateWorkflowCondition(it) }
            }

            is com.zoya.ai.assistant.tasks.workflow.Condition.Not -> {
                !evaluateWorkflowCondition(condition.condition)
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun accessibilityDisabled(): AutomationResult = AutomationResult.permissionDenied(
        "ACCESSIBILITY",
        "Accessibility service is not enabled. Enable 'Zoya AI Assistant' in Accessibility settings."
    )

    fun shutdown() {
        cancelled.set(true)
        cameraController?.release()
        microphoneController.release()
    }
}
