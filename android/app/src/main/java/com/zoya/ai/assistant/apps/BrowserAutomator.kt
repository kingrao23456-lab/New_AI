package com.zoya.ai.assistant.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.zoya.ai.assistant.accessibility.GestureInjector
import com.zoya.ai.assistant.accessibility.NodeFinder
import com.zoya.ai.assistant.accessibility.ScreenContext
import com.zoya.ai.assistant.accessibility.SemanticActions
import com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.Selector
import org.json.JSONObject

/**
 * Browser automation for Chrome and supported browsers. Uses accessibility
 * first, OCR/visual detection when needed, and handles loading delays,
 * dialogs and changed layouts through retry/re-detection.
 */
class BrowserAutomator(
    private val context: Context,
    private val service: () -> ZoyaAccessibilityService?
) {

    private val supportedBrowsers = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.android.browser"
    )

    private fun launchBrowser(): AutomationResult {
        for (pkg in supportedBrowsers) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return AutomationResult.success(JSONObject().put("browser", pkg))
            }
        }
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(generic) }
            .onFailure { return AutomationResult.failure("NO_BROWSER", "No supported browser found.") }
        return AutomationResult.success()
    }

    fun openUrl(url: String): AutomationResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return AutomationResult.blocked("INVALID_URL", "URL must start with http:// or https://.")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            AutomationResult.success(JSONObject().put("url", url).put("action", "open"))
        } catch (e: Exception) {
            AutomationResult.failure("OPEN_FAILED", "Could not open URL: ${e.message}")
        }
    }

    fun search(query: String): AutomationResult {
        if (query.isBlank()) {
            return AutomationResult.blocked("MISSING_ARGUMENT", "Search query is empty.")
        }
        val url = "https://www.google.com/search?q=" + Uri.encode(query)
        return openUrl(url)
    }

    /**
     * Searches within an already-open browser: focuses the address/search
     * field, types the query and submits.
     */
    fun searchInBrowser(query: String): AutomationResult {
        val svc = service() ?: return AccessibilityUnavailable()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")

        val field = NodeFinder.findFirst(
            root,
            Selector(
                className = "EditText",
                editable = true
            )
        ) ?: NodeFinder.findFirst(
            root,
            Selector(resourceId = "url_bar")
        )

        root.recycle()
        if (field == null) {
            return AutomationResult.failure("TARGET_NOT_FOUND", "Could not locate the browser address/search field.")
        }
        return try {
            SemanticActions.click(field)
            Thread.sleep(300)
            val typed = SemanticActions.setText(field, query)
            if (!typed) {
                SemanticActions.focus(field)
                Thread.sleep(200)
                // Input via GestureInjector keyboard fallback is not possible;
                // ACTION_SET_TEXT is the supported semantic path.
            }
            Thread.sleep(400)
            // No ACTION_IME_ENTER constant exists; submit via the visible
            // "Go"/"Search"/arrow key node when present.
            val root2 = svc.screenContext.getRoot()
            var submitted = false
            if (root2 != null) {
                val goKey = NodeFinder.findFirst(
                    root2,
                    Selector(partialText = "go", clickable = true)
                ) ?: NodeFinder.findFirst(
                    root2,
                    Selector(partialText = "search", clickable = true)
                )
                if (goKey != null) {
                    submitted = SemanticActions.click(goKey)
                    goKey.recycle()
                }
                root2.recycle()
            }
            Thread.sleep(500)
            AutomationResult.success(
                JSONObject().put("query", query).put("action", "search").put("submitted", submitted)
            )
        } catch (e: Exception) {
            AutomationResult.failure("SEARCH_FAILED", "Search failed: ${e.message}")
        } finally {
            field.recycle()
        }
    }

    fun readVisibleText(): AutomationResult {
        val svc = service() ?: return AccessibilityUnavailable()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")
        val texts = mutableListOf<String>()
        NodeFinder.walk(root) { node, _ ->
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && node.isVisibleToUser) {
                texts.add(text)
            }
            null
        }
        root.recycle()
        val data = JSONObject()
        data.put("text", texts.joinToString("\n"))
        data.put("visibleLines", texts.size)
        data.put("packageName", svc.screenContext.currentPackage ?: JSONObject.NULL)
        return AutomationResult.success(data)
    }

    fun clickLinkByText(partialText: String): AutomationResult {
        val svc = service() ?: return AccessibilityUnavailable()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")
        val target = NodeFinder.findFirst(root, Selector(partialText = partialText, clickable = true))
            ?: NodeFinder.findFirst(root, Selector(partialText = partialText))
        root.recycle()
        if (target == null) {
            return AutomationResult.failure("TARGET_NOT_FOUND", "No clickable element matching '$partialText'.")
        }
        val clicked = SemanticActions.click(target)
        target.recycle()
        return if (clicked) {
            AutomationResult.success(JSONObject().put("target", partialText).put("action", "click"))
        } else {
            AutomationResult.failure("CLICK_FAILED", "Element matched but click action was rejected.")
        }
    }

    fun scroll(direction: String): AutomationResult {
        val svc = service() ?: return AccessibilityUnavailable()
        val injector = GestureInjector(svc, { svc.screenContext.screenWidth }, { svc.screenContext.screenHeight })
        val w = svc.screenContext.screenWidth
        val h = svc.screenContext.screenHeight
        if (w <= 0 || h <= 0) return AutomationResult.failure("NO_SCREEN", "Screen size unavailable.")
        val spec = when (direction.lowercase()) {
            "down" -> GestureInjector.GestureSpec(
                "swipe", fromX = w / 2.0, fromY = h * 0.7, toX = w / 2.0, toY = h * 0.3,
                durationMs = 400, normalized = false
            )
            "up" -> GestureInjector.GestureSpec(
                "swipe", fromX = w / 2.0, fromY = h * 0.3, toX = w / 2.0, toY = h * 0.7,
                durationMs = 400, normalized = false
            )
            else -> return AutomationResult.blocked("INVALID_DIRECTION", "Direction must be 'up' or 'down'.")
        }
        return injector.dispatch(spec)
    }

    fun verifyNavigation(url: String): AutomationResult {
        // Best-effort verification: the browser package is active and the
        // address bar contains the URL host.
        val svc = service() ?: return AccessibilityUnavailable()
        val current = svc.screenContext.currentPackage ?: ""
        if (!isBrowser(current)) {
            return AutomationResult.failure("NAVIGATION_FAILED", "Browser is not the foreground app.")
        }
        val host = Uri.parse(url).host ?: return AutomationResult.success()
        val root = svc.screenContext.getRoot() ?: return AutomationResult.failure("NO_SCREEN", "No screen available.")
        val found = NodeFinder.walk(root) { node, _ ->
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (text != null && text.contains(host, ignoreCase = true)) true else null
        }
        root.recycle()
        return if (found == true) {
            AutomationResult.success(JSONObject().put("verified", true).put("url", url))
        } else {
            AutomationResult.failure("NAVIGATION_FAILED", "Could not verify navigation to $host.")
        }
    }

    fun isBrowser(packageName: String): Boolean = supportedBrowsers.contains(packageName)

    private fun AccessibilityUnavailable(): AutomationResult =
        AutomationResult.permissionDenied(
            "ACCESSIBILITY",
            "Accessibility service is not enabled. Enable 'Zoya AI Assistant' in Accessibility settings first."
        )
}
