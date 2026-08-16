package com.zoya.ai.assistant.automation

/**
 * Central registry of per-app automation files. Register each app's
 * AppAutomation here as it's built and tested. Once every planned app is
 * done, register a DEFAULT automation (any packageName) as the fallback
 * for apps that don't have (or fail) a dedicated file.
 *
 * Currently empty on purpose — generic automation has been disabled.
 * Nothing is automated until an app is registered here.
 */
object AutomationRegistry {

    private val perApp = mutableMapOf<String, AppAutomation>()

    /** Fallback used when no per-app automation is registered/handles a command. */
    private var default: AppAutomation? = null

    fun register(automation: AppAutomation) {
        perApp[automation.packageName] = automation
    }

    fun registerDefault(automation: AppAutomation) {
        default = automation
    }

    fun forPackage(packageName: String?): AppAutomation? {
        if (packageName == null) return default
        return perApp[packageName] ?: default
    }

    /**
     * Scans every registered per-app automation for one that declares it
     * handles [command], regardless of which app is currently in the
     * foreground. Needed for "open the app" style commands (e.g.
     * openWhatsApp) which must work even before that app is on screen —
     * at that point the foreground app is whatever the user is currently
     * in, not the target app, so a foreground-package lookup would miss it.
     */
    fun anyHandling(command: String): AppAutomation? =
        perApp.values.firstOrNull { it.handles(command) } ?: default?.takeIf { it.handles(command) }
}
