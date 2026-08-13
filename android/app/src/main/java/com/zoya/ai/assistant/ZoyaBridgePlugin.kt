package com.zoya.ai.assistant

import android.content.Intent
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.zoya.ai.assistant.core.engine.AutomationEngine
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.ResultStatus
import com.zoya.ai.assistant.core.security.SecretsRedactor
import com.zoya.ai.assistant.vision.CaptureController

/**
 * ZoyaNativeBridge — the secure native bridge between the Capacitor web layer
 * and the Android Automation Engine.
 *
 * Every method resolves with a structured result:
 *   { status, ok, data?, error?, meta? }
 * where status is one of SUCCESS / FAILURE / PERMISSION_DENIED / TIMEOUT /
 * UNSUPPORTED / CANCELLED / BLOCKED. Blocked or failed actions are never
 * reported as successful.
 */
@CapacitorPlugin(
    name = "ZoyaNativeBridge",
    permissions = [
        Permission(
            alias = "camera",
            strings = ["android.permission.CAMERA"]
        ),
        Permission(
            alias = "microphone",
            strings = ["android.permission.RECORD_AUDIO"]
        )
    ]
)
class ZoyaBridgePlugin : Plugin() {

    private val engine: AutomationEngine get() = AutomationEngine.get()

    private val captureController: CaptureController?
        get() = Companion.captureController

    override fun load() {
        super.load()
        // Defensive: never let a startup failure crash the host activity.
        runCatching {
            AutomationEngine.init(context)
            AutomationEngine.get().resetCancellation()
            bridge.activity?.let { AutomationEngine.get().setActivity(it) }
            Companion.captureController = CaptureController(context) { intent ->
                val call = capturePendingCall
                if (call != null) {
                    startActivityForResult(call, intent, REQUEST_CAPTURE)
                }
            }
        }.onFailure { Log.e(TAG, "Engine init failed", it) }
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            captureController?.handleActivityResult(resultCode, data)
        }
    }

    private fun resolve(call: PluginCall, result: AutomationResult) {
        if (call.isReleased()) return
        // AutomationResult.toJson() returns a plain org.json.JSONObject, but
        // Capacitor requires a JSObject. Build one explicitly (a checked cast
        // would throw ClassCastException for non-ok results).
        val data = JSObject(result.toJson().toString())
        if (result.ok) {
            call.resolve(data)
        } else {
            val err = JSObject()
            if (result.errorCode != null) err.put("code", result.errorCode)
            if (result.errorMessage != null) err.put("message", result.errorMessage)
            call.reject(
                result.errorMessage ?: result.status.name,
                result.errorCode ?: result.status.name,
                data
            )
        }
    }

    // ------------------------------------------------------------------
    // Core command executor
    // ------------------------------------------------------------------

    @PluginMethod
    fun execute(call: PluginCall) {
        val command = call.getString("command") ?: ""
        val args = call.getObject("args") ?: JSObject()
        val consent = call.getBoolean("consent", false) == true

        val argsMap = HashMap<String, Any?>()
        args.keys().forEach { key -> argsMap[key] = args.opt(key) }

        // Log redacted args only.
        Log.i(TAG, "execute: $command args=${SecretsRedactor.redactJson(args)}")

        if (command == "takePhoto" || command == "startRecording") {
            // Permission request flow happens first.
            val required = permissionAliasFor(command)
            if (required != null && !hasAliasPermission(required)) {
                val result = AutomationResult.permissionDenied(
                    required,
                    "Permission '$required' is required. Call requestPermission first."
                )
                resolve(call, result)
                return
            }
        }

        val result = engine.execute(command, argsMap, consent)
        resolve(call, result)
    }

    @PluginMethod
    fun cancelOperation(call: PluginCall) {
        engine.cancelCurrentOperation()
        call.resolve(JSObject().put("cancelled", true))
    }

    @PluginMethod
    fun isNative(call: PluginCall) {
        call.resolve(JSObject().put("native", true))
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        val alias = call.getString("permission") ?: ""
        when (alias) {
            "camera", "microphone" -> {
                requestPermissionForAlias(alias, call)
            }
            "accessibility" -> {
                com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService.openSettings(context)
                call.resolve(JSObject().put("status", "settings_opened"))
            }
            "overlay" -> {
                com.zoya.ai.assistant.apps.SettingsLauncher(context).openOverlaySettings()
                call.resolve(JSObject().put("status", "settings_opened"))
            }
            "write_settings" -> {
                com.zoya.ai.assistant.apps.SettingsLauncher(context).openSystemWriteSettings()
                call.resolve(JSObject().put("status", "settings_opened"))
            }
            else -> {
                call.reject("Unknown permission alias '$alias'", "UNKNOWN_PERMISSION")
            }
        }
    }

    @PluginMethod
    fun getPermissionStates(call: PluginCall) {
        val states = JSObject()
        states.put("camera", getPermissionState("camera").name)
        states.put("microphone", getPermissionState("microphone").name)
        states.put(
            "accessibility",
            if (com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService.isEnabled(context)) "granted" else "denied"
        )
        states.put("overlay", android.provider.Settings.canDrawOverlays(context).let { if (it) "granted" else "denied" })
        states.put("write_settings", android.provider.Settings.System.canWrite(context).let { if (it) "granted" else "denied" })
        call.resolve(states)
    }

    private fun permissionAliasFor(command: String): String? {
        return when (command) {
            "takePhoto" -> "camera"
            "startRecording" -> "microphone"
            else -> null
        }
    }

    private fun hasAliasPermission(alias: String): Boolean {
        return getPermissionState(alias) == PermissionState.GRANTED
    }

    private fun requestPermissionForAlias(alias: String, call: PluginCall) {
        when (alias) {
            "camera" -> requestAllPermissions(call, "cameraPermissionCallback")
            "microphone" -> requestAllPermissions(call, "microphonePermissionCallback")
        }
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        val granted = getPermissionState("camera") == PermissionState.GRANTED
        call.resolve(JSObject().put("camera", granted))
    }

    @PermissionCallback
    private fun microphonePermissionCallback(call: PluginCall) {
        val granted = getPermissionState("microphone") == PermissionState.GRANTED
        call.resolve(JSObject().put("microphone", granted))
    }

    // ------------------------------------------------------------------
    // Screen capture (MediaProjection) for OCR / verification
    // ------------------------------------------------------------------

    @Volatile
    private var capturePendingCall: PluginCall? = null

    @PluginMethod
    fun captureScreenForOcr(call: PluginCall) {
        if (capturePendingCall != null) {
            call.reject("A capture request is already in progress.", "CAPTURE_BUSY")
            return
        }
        capturePendingCall = call
        captureController?.requestCaptureOnce { result ->
            capturePendingCall = null
            resolve(call, result)
        }
    }

    @PluginMethod
    fun stopScreenCapture(call: PluginCall) {
        com.zoya.ai.assistant.vision.ScreenCaptureService.stop(context)
        call.resolve(JSObject().put("stopped", true))
    }

    // ------------------------------------------------------------------
    // Task / workflow status events
    // ------------------------------------------------------------------

    @PluginMethod
    fun subscribeToEvents(call: PluginCall) {
        eventUnsubscribe?.invoke()
        eventUnsubscribe = engine.logStore.addListener { entry ->
            val activity = bridge.activity
            val evt = JSObject(entry.toString())
            if (activity != null) {
                activity.runOnUiThread { notifyListeners(EVENT_LOG, evt) }
            } else {
                notifyListeners(EVENT_LOG, evt)
            }
        }
        call.resolve(JSObject().put("subscribed", true))
    }

    @PluginMethod
    fun unsubscribeFromEvents(call: PluginCall) {
        eventUnsubscribe?.invoke()
        eventUnsubscribe = null
        call.resolve(JSObject().put("unsubscribed", true))
    }

    // ------------------------------------------------------------------
    // Biometric authentication for sensitive settings / credentials
    // ------------------------------------------------------------------

    @PluginMethod
    fun biometricAuth(call: PluginCall) {
        val activity = bridge.activity
        if (activity !is androidx.fragment.app.FragmentActivity) {
            call.reject("Biometric authentication requires the activity context.", "NO_ACTIVITY")
            return
        }
        val storage = com.zoya.ai.assistant.core.security.SecureStorage(context)
        if (!storage.canUseBiometric(context)) {
            call.resolve(JSObject().put("authenticated", false).put("reason", "no_biometric"))
            return
        }
        activity.runOnUiThread {
            storage.authenticateWithBiometric(
                activity,
                title = "Zoya Secure Access",
                subtitle = "Authenticate to access sensitive automation settings",
                onAuthenticated = {
                    if (!call.isReleased()) call.resolve(JSObject().put("authenticated", true))
                },
                onCancelled = {
                    if (!call.isReleased()) call.resolve(JSObject().put("authenticated", false).put("reason", "cancelled"))
                },
                onError = { code, _ ->
                    if (!call.isReleased()) call.resolve(JSObject().put("authenticated", false).put("reason", "error").put("code", code))
                }
            )
        }
    }

    private var eventUnsubscribe: (() -> Unit)? = null

    private fun notifyEvent(event: String, data: JSObject) {
        notifyListeners(event, data)
    }

    override fun handleOnDestroy() {
        eventUnsubscribe?.invoke()
        eventUnsubscribe = null
        engine.setActivity(null)
        Companion.captureController = null
        super.handleOnDestroy()
    }

    companion object {
        private const val TAG = "ZoyaBridge"
        private const val REQUEST_CAPTURE = 4101
        private const val EVENT_LOG = "automationLog"

        @Volatile
        var captureController: CaptureController? = null
            private set
    }
}
