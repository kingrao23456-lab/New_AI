package com.zoya.ai.assistant.automation.apps

import android.view.accessibility.AccessibilityNodeInfo
import com.zoya.ai.assistant.accessibility.GestureInjector
import com.zoya.ai.assistant.accessibility.NodeFinder
import com.zoya.ai.assistant.accessibility.ZoyaAccessibilityService
import com.zoya.ai.assistant.apps.AppManager
import com.zoya.ai.assistant.automation.AppAutomation
import com.zoya.ai.assistant.core.engine.AutomationEngine
import com.zoya.ai.assistant.core.model.AutomationResult
import com.zoya.ai.assistant.core.model.Selector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated, isolated WhatsApp automation module.
 *
 * This file only knows about WhatsApp's UI. It never touches other apps'
 * automation and other apps' automation never touches this file. If a
 * WhatsApp version changes its layout, only this file needs to change.
 *
 * Detection strategy per command follows the layered approach requested in
 * the PRD: accessibility text -> content description -> resource id ->
 * class/role -> coordinate fallback (only for raw gesture commands like
 * tap/swipe that the caller already supplies coordinates for).
 */
class WhatsAppAutomation : AppAutomation {

    override val packageName: String = "com.whatsapp"

    // ---- duplicate-action protection state ----
    private var lastSentKey: String? = null
    private var lastSentAtMs: Long = 0L
    private val DUPLICATE_WINDOW_MS = 4000L

    // ---- debug log (in-memory ring buffer, no secrets ever stored) ----
    private val debugLog = ArrayDeque<String>()
    private fun debug(line: String) {
        synchronized(debugLog) {
            debugLog.addLast("${System.currentTimeMillis()}: $line")
            while (debugLog.size > 200) debugLog.removeFirst()
        }
    }

    private val supportedCommands = setOf(
        "openWhatsApp", "closeWhatsApp", "goBack", "getCurrentScreen",
        "openChats", "openStatus", "openCalls", "openSearch", "openNewChat",
        "search", "clearSearch", "openSearchResult",
        "openChat", "openGroup",
        "sendMessage", "sendMultilineMessage",
        "replyToMessage", "forwardMessage", "copyMessage", "deleteMessage",
        "starMessage", "reactToMessage", "selectMessage",
        "openContactInfo", "openGroupInfo",
        "muteChat", "unmuteChat", "pinChat", "unpinChat",
        "archiveChat", "unarchiveChat", "markChatRead", "markChatUnread",
        "clearChat", "deleteChat", "leaveGroup",
        "sendPhoto", "sendVideo", "sendDocument", "sendMedia", "openCamera", "cancelMediaSelection",
        "startVoiceMessage", "stopVoiceMessage", "sendVoiceMessage", "cancelVoiceMessage",
        "openStatusViewer", "nextStatus", "previousStatus", "replyToStatus",
        "createTextStatus", "createPhotoStatus", "createVideoStatus", "publishStatus",
        "startVoiceCall", "startVideoCall", "endCall", "openCallInfo",
        "openSettings", "openSettingsSection",
        "tap", "longPress", "swipe", "scroll", "doubleTap", "typeText",
        "scrollUntilFound", "scrollToTop", "scrollToBottom",
        "findElement", "waitForElement"
    )

    override fun handles(command: String): Boolean = command in supportedCommands

    override fun execute(
        engine: AutomationEngine,
        command: String,
        args: Map<String, Any?>,
        timeoutMs: Long
    ): AutomationResult {
        val svc = ZoyaAccessibilityService.instance
            ?: return AutomationResult.blocked("ACCESSIBILITY_DISABLED", "Accessibility service is not connected.")
        debug("command=$command args=$args")

        return try {
            when (command) {
                "openWhatsApp" -> openWhatsApp(svc)
                "closeWhatsApp" -> closeWhatsApp(svc)
                "goBack" -> pressBack(svc)
                "getCurrentScreen" -> getCurrentScreen(svc)

                "openChats" -> tapBottomTab(svc, "Chats")
                "openStatus" -> tapBottomTab(svc, "Updates", "Status")
                "openCalls" -> tapBottomTab(svc, "Calls")
                "openSearch" -> openSearch(svc)
                "openNewChat" -> openNewChat(svc)

                "search" -> search(svc, textArg(args, "query"))
                "clearSearch" -> clearSearch(svc)
                "openSearchResult" -> openSearchResult(svc, textArg(args, "target"))

                "openChat", "openGroup" -> openChat(svc, textArg(args, "target"))

                "sendMessage", "sendMultilineMessage" -> sendMessage(svc, textArg(args, "target"), textArg(args, "message"))

                "replyToMessage" -> replyToMessage(svc, textArg(args, "messageText"), textArg(args, "reply"))
                "forwardMessage" -> messageLongPressThenMenu(svc, textArg(args, "messageText"), "Forward")
                "copyMessage" -> messageLongPressThenMenu(svc, textArg(args, "messageText"), "Copy")
                "deleteMessage" -> deleteMessage(svc, textArg(args, "messageText"), args["confirmed"] == true)
                "starMessage" -> messageLongPressThenMenu(svc, textArg(args, "messageText"), "Star")
                "reactToMessage" -> reactToMessage(svc, textArg(args, "messageText"), textArg(args, "emoji"))
                "selectMessage" -> messageLongPress(svc, textArg(args, "messageText"))

                "openContactInfo" -> openChatOptionsThen(svc, "Contact info", "View contact")
                "openGroupInfo" -> openChatOptionsThen(svc, "Group info")
                "muteChat" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Mute", "Mute notifications")
                "unmuteChat" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Unmute")
                "pinChat" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Pin", "Pin chat")
                "unpinChat" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Unpin", "Unpin chat")
                "archiveChat" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Archive")
                "unarchiveChat" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Unarchive")
                "markChatRead" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Mark as read")
                "markChatUnread" -> chatListLongPressThenMenu(svc, textArg(args, "target"), "Mark as unread")
                "clearChat" -> destructiveChatAction(svc, textArg(args, "target"), "Clear chat", args["confirmed"] == true)
                "deleteChat" -> destructiveChatAction(svc, textArg(args, "target"), "Delete chat", args["confirmed"] == true)
                "leaveGroup" -> leaveGroup(svc, args["confirmed"] == true)

                "sendPhoto", "sendVideo", "sendMedia" -> sendMedia(svc, textArg(args, "target"), textArg(args, "caption"), args["confirmed"] == true)
                "sendDocument" -> sendDocument(svc, textArg(args, "target"), textArg(args, "fileName"), args["confirmed"] == true)
                "openCamera" -> openCamera(svc)
                "cancelMediaSelection" -> pressBack(svc)

                "startVoiceMessage" -> startVoiceMessage(svc)
                "stopVoiceMessage", "sendVoiceMessage" -> stopAndSendVoiceMessage(svc)
                "cancelVoiceMessage" -> cancelVoiceMessage(svc)

                "openStatusViewer" -> openStatusViewer(svc, textArg(args, "target"))
                "nextStatus" -> tapScreenFraction(svc, 0.85, 0.5)
                "previousStatus" -> tapScreenFraction(svc, 0.15, 0.5)
                "replyToStatus" -> replyToStatus(svc, textArg(args, "reply"))
                "createTextStatus" -> createTextStatus(svc, textArg(args, "text"))
                "createPhotoStatus" -> createMediaStatus(svc, "photo")
                "createVideoStatus" -> createMediaStatus(svc, "video")
                "publishStatus" -> tapBySelector(svc, Selector(contentDescriptionPartial = "Send"), "publish status")

                "startVoiceCall" -> startCall(svc, textArg(args, "target"), video = false)
                "startVideoCall" -> startCall(svc, textArg(args, "target"), video = true)
                "endCall" -> tapBySelector(svc, Selector(contentDescriptionPartial = "End call"), "end call")
                "openCallInfo" -> tapBySelector(svc, Selector(partialText = "Call info"), "call info")

                "openSettings" -> openSettings(svc)
                "openSettingsSection" -> openSettingsSection(svc, textArg(args, "section"))

                "tap" -> genericTap(svc, args)
                "longPress" -> genericLongPress(svc, args)
                "doubleTap" -> genericDoubleTap(svc, args)
                "swipe" -> genericSwipe(svc, args)
                "scroll" -> genericScroll(svc, args)
                "typeText" -> typeIntoFocused(svc, textArg(args, "text"))
                "scrollUntilFound" -> scrollUntilFound(svc, textArg(args, "target"))
                "scrollToTop" -> scrollExtreme(svc, toTop = true)
                "scrollToBottom" -> scrollExtreme(svc, toTop = false)

                "findElement" -> findElementResult(svc, args)
                "waitForElement" -> waitForElementResult(svc, args, timeoutMs)

                else -> AutomationResult.unsupported("WhatsApp automation has no handler for '$command'.")
            }
        } catch (e: Exception) {
            debug("EXCEPTION: ${e.message}")
            AutomationResult.failure("ACTION_FAILED", "WhatsApp automation error on '$command': ${e.message}")
        }
    }

    // =====================================================================
    // Navigation & lifecycle
    // =====================================================================

    private fun openWhatsApp(svc: ZoyaAccessibilityService): AutomationResult {
        val appManager = AppManager(svc.applicationContext)
        val result = appManager.launchApp(packageName)
        if (!result.ok) return result
        waitFor(600)
        return result
    }

    private fun closeWhatsApp(svc: ZoyaAccessibilityService): AutomationResult {
        svc.performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        waitFor(300)
        return AutomationResult.success(message = "Went to home screen (WhatsApp backgrounded).")
    }

    private fun pressBack(svc: ZoyaAccessibilityService): AutomationResult {
        val ok = svc.performGlobalActionCompat(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor(250)
        return if (ok) AutomationResult.success() else AutomationResult.failure("ACTION_FAILED", "Back action failed.")
    }

    /**
     * Classifies the current WhatsApp screen using visible text/resource-id
     * signals rather than a fixed layout, so it keeps working across
     * WhatsApp versions.
     */
    private fun getCurrentScreen(svc: ZoyaAccessibilityService): AutomationResult {
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val pkg = svc.screenContext.currentPackage
        val data = JSONObject()
        try {
            if (pkg != packageName) {
                data.put("screen", "closed")
                return AutomationResult.success(data)
            }
            val screen = when {
                hasText(root, "Camera, video shooting button") || hasResourceId(root, "camera_btn") -> "home"
                hasText(root, "Search or start new chat") -> "chats"
                hasText(root, "Add status") -> "updates"
                hasText(root, "Search calls") -> "calls"
                hasText(root, "Type a message") -> "chat"
                hasContentDescPartial(root, "Group info") -> "group_chat"
                hasText(root, "Group settings") -> "group_info"
                hasText(root, "Block") && hasText(root, "Share Contact") -> "contact_info"
                hasText(root, "Account") && hasText(root, "Privacy") && hasText(root, "Storage and data") -> "settings"
                hasText(root, "Privacy") && hasText(root, "Last seen and online") -> "privacy_settings"
                hasResourceId(root, "gallery_grid") || hasText(root, "Recents") -> "media_picker"
                hasContentDescPartial(root, "Reply to status") -> "status_viewer"
                hasText(root, "My status") -> "status_editor"
                isDialogPresent(root) -> "dialog"
                else -> "unknown"
            }
            data.put("screen", screen)
            data.put("packageName", pkg)
            return AutomationResult.success(data)
        } finally {
            root.recycle()
        }
    }

    private fun tapBottomTab(svc: ZoyaAccessibilityService, vararg labels: String): AutomationResult {
        for (label in labels) {
            val root = requireRoot(svc) ?: continue
            try {
                val node = NodeFinder.findFirst(root, Selector(contentDescriptionPartial = label))
                    ?: NodeFinder.findFirst(root, Selector(partialText = label))
                if (node != null) {
                    val r = tapNode(svc, node)
                    node.recycle()
                    if (r.ok) return r
                }
            } finally {
                root.recycle()
            }
        }
        return AutomationResult.failure("ELEMENT_NOT_FOUND", "Could not find tab: ${labels.joinToString("/")}.")
    }

    private fun openSearch(svc: ZoyaAccessibilityService): AutomationResult =
        tapBySelector(svc, Selector(contentDescriptionPartial = "Search"), "search icon")

    private fun openNewChat(svc: ZoyaAccessibilityService): AutomationResult =
        tapBySelector(svc, Selector(contentDescriptionPartial = "New chat"), "new chat button")

    // =====================================================================
    // Search
    // =====================================================================

    private fun search(svc: ZoyaAccessibilityService, query: String?): AutomationResult {
        if (query.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Search query is required.")
        val openResult = openSearch(svc)
        if (!openResult.ok) {
            // Search field might already be open/focused.
        }
        waitFor(300)
        val typed = typeIntoFocused(svc, query)
        if (!typed.ok) return typed
        waitFor(400)
        return listSearchMatches(svc, query)
    }

    private fun clearSearch(svc: ZoyaAccessibilityService): AutomationResult =
        tapBySelector(svc, Selector(contentDescriptionPartial = "Clear search"), "clear search")

    /** Returns all visible matches so the AI can ask the user to disambiguate. */
    private fun listSearchMatches(svc: ZoyaAccessibilityService, query: String): AutomationResult {
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        try {
            val allClickable = NodeFinder.findAll(root, Selector(clickable = true), maxResults = 100)
            val names = allClickable
                .mapNotNull { it.text?.toString() }
                .filter { it.contains(query, ignoreCase = true) }
                .distinct()
            allClickable.forEach { it.recycle() }
            val data = JSONObject().put("matches", JSONArray(names))
            return when {
                names.isEmpty() -> AutomationResult.failure("CONTACT_NOT_FOUND", "No contact, group or chat matched '$query'.", data)
                names.size == 1 -> AutomationResult.success(data)
                else -> AutomationResult(
                    status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                    data = data,
                    errorCode = "MULTIPLE_MATCHES",
                    errorMessage = "Multiple matches for '$query': ${names.joinToString(", ")}. Ask the user which one."
                )
            }
        } finally {
            root.recycle()
        }
    }

    private fun openSearchResult(svc: ZoyaAccessibilityService, target: String?): AutomationResult {
        if (target.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Target is required.")
        return tapBySelector(svc, Selector(partialText = target), "search result '$target'")
    }

    // =====================================================================
    // Chats
    // =====================================================================

    /** Opens a chat by contact/group name, searching first if not already visible. */
    private fun openChat(svc: ZoyaAccessibilityService, target: String?): AutomationResult {
        if (target.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Chat target (contact/group name) is required.")

        // Try tapping directly if already visible in the current chat list.
        val root = requireRoot(svc)
        if (root != null) {
            val direct = NodeFinder.findAll(root, Selector(partialText = target, clickable = true), maxResults = 5)
            root.recycle()
            if (direct.size == 1) {
                val r = tapNode(svc, direct[0])
                direct[0].recycle()
                if (r.ok) {
                    waitFor(500)
                    return r
                }
            } else {
                direct.forEach { it.recycle() }
                if (direct.size > 1) {
                    return AutomationResult(
                        status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                        errorCode = "MULTIPLE_MATCHES",
                        errorMessage = "Multiple chats match '$target'. Ask the user which one."
                    )
                }
            }
        }

        // Fall back to search.
        val searchResult = search(svc, target)
        if (!searchResult.ok) return AutomationResult.failure("CHAT_NOT_FOUND", "Could not find chat for '$target'.")
        waitFor(300)
        val opened = tapBySelector(svc, Selector(partialText = target), "chat '$target'")
        if (opened.ok) waitFor(500)
        return opened
    }

    private fun openMessageField(svc: ZoyaAccessibilityService): AccessibilityNodeInfo? {
        val root = requireRoot(svc) ?: return null
        val node = NodeFinder.findFirst(root, Selector(contentDescriptionPartial = "Type a message"))
            ?: NodeFinder.findFirst(root, Selector(partialText = "Message"))
        root.recycle()
        return node
    }

    private fun sendMessage(svc: ZoyaAccessibilityService, target: String?, message: String?): AutomationResult {
        if (message.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Message text is required.")

        if (!target.isNullOrBlank()) {
            val opened = openChat(svc, target)
            if (!opened.ok) return opened
        }

        val key = "${target ?: "current"}::$message"
        val now = System.currentTimeMillis()
        if (key == lastSentKey && now - lastSentAtMs < DUPLICATE_WINDOW_MS) {
            return AutomationResult.blocked("ACTION_FAILED", "Duplicate send suppressed (same message sent moments ago).")
        }

        val field = openMessageField(svc)
            ?: return AutomationResult.failure("ELEMENT_NOT_FOUND", "Message input field not found. Are you in a chat?")
        val focusResult = tapNode(svc, field)
        field.recycle()
        if (!focusResult.ok) return focusResult

        val typeResult = typeIntoFocused(svc, message)
        if (!typeResult.ok) return typeResult

        waitFor(200)
        val sendResult = tapBySelector(svc, Selector(contentDescriptionPartial = "Send"), "send button")
        if (!sendResult.ok) return AutomationResult.failure("SEND_FAILED", "Could not tap the send button.")

        // Verify: the message input should be empty again after sending.
        waitFor(400)
        val fieldAfter = openMessageField(svc)
        val stillHasText = fieldAfter?.text?.toString()?.isNotBlank() == true
        fieldAfter?.recycle()
        if (stillHasText) {
            return AutomationResult.failure("SEND_FAILED", "Send button was tapped but message field still has text; send likely failed.")
        }

        lastSentKey = key
        lastSentAtMs = now
        return AutomationResult.success(message = "Message sent successfully.")
    }

    private fun findMessageBubble(svc: ZoyaAccessibilityService, messageText: String?): AccessibilityNodeInfo? {
        if (messageText.isNullOrBlank()) return null
        val root = requireRoot(svc) ?: return null
        val node = NodeFinder.findFirst(root, Selector(partialText = messageText))
        root.recycle()
        return node
    }

    private fun messageLongPress(svc: ZoyaAccessibilityService, messageText: String?): AutomationResult {
        val bubble = findMessageBubble(svc, messageText)
            ?: return AutomationResult.failure("ELEMENT_NOT_FOUND", "Message '$messageText' not found on screen. Scroll to it first.")
        val r = longPressNode(svc, bubble)
        bubble.recycle()
        waitFor(300)
        return r
    }

    private fun messageLongPressThenMenu(svc: ZoyaAccessibilityService, messageText: String?, vararg menuLabels: String): AutomationResult {
        val lp = messageLongPress(svc, messageText)
        if (!lp.ok) return lp
        return tapAnyBySelector(svc, menuLabels.map { Selector(contentDescriptionPartial = it) } +
            menuLabels.map { Selector(partialText = it) }, menuLabels.joinToString("/"))
    }

    private fun deleteMessage(svc: ZoyaAccessibilityService, messageText: String?, confirmed: Boolean): AutomationResult {
        if (!confirmed) {
            return AutomationResult(
                status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                errorCode = "CONFIRMATION_REQUIRED",
                errorMessage = "Deleting a message is destructive. Ask the user to confirm, then retry with confirmed=true."
            )
        }
        val lp = messageLongPress(svc, messageText)
        if (!lp.ok) return lp
        val deleteTap = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Delete"), Selector(partialText = "Delete")), "delete")
        if (!deleteTap.ok) return deleteTap
        waitFor(300)
        // A confirmation dialog usually follows ("Delete for me" / "Delete for everyone").
        return tapAnyBySelector(svc, listOf(Selector(partialText = "Delete for me"), Selector(partialText = "Delete")), "confirm delete")
    }

    private fun reactToMessage(svc: ZoyaAccessibilityService, messageText: String?, emoji: String?): AutomationResult {
        val lp = messageLongPress(svc, messageText)
        if (!lp.ok) return lp
        if (!emoji.isNullOrBlank()) {
            val emojiTap = tapBySelector(svc, Selector(exactText = emoji), "emoji '$emoji'")
            if (emojiTap.ok) return emojiTap
        }
        return AutomationResult.failure("UNSUPPORTED_ACTION", "Could not find the requested reaction option.")
    }

    private fun replyToMessage(svc: ZoyaAccessibilityService, messageText: String?, reply: String?): AutomationResult {
        if (reply.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Reply text is required.")
        val lp = messageLongPress(svc, messageText)
        if (!lp.ok) return lp
        val replyTap = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Reply"), Selector(partialText = "Reply")), "reply")
        if (!replyTap.ok) return replyTap
        waitFor(300)
        return sendMessage(svc, target = null, message = reply)
    }

    // ---- chat list level actions (mute/pin/archive/etc via long press on the chat row) ----

    private fun chatListLongPressThenMenu(svc: ZoyaAccessibilityService, target: String?, vararg menuLabels: String): AutomationResult {
        if (target.isNullOrBlank()) {
            // Act on whatever chat is currently open, via its options menu (three-dot / chat name header).
            return openChatOptionsThen(svc, *menuLabels)
        }
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val row = NodeFinder.findFirst(root, Selector(partialText = target))
        root.recycle()
        if (row == null) return AutomationResult.failure("CHAT_NOT_FOUND", "Chat '$target' not visible. Search for it first.")
        val lp = longPressNode(svc, row)
        row.recycle()
        if (!lp.ok) return lp
        waitFor(300)
        return tapAnyBySelector(svc, menuLabels.map { Selector(contentDescriptionPartial = it) } +
            menuLabels.map { Selector(partialText = it) }, menuLabels.joinToString("/"))
    }

    private fun openChatOptionsThen(svc: ZoyaAccessibilityService, vararg menuLabels: String): AutomationResult {
        val menuOpen = tapBySelector(svc, Selector(contentDescriptionPartial = "More options"), "chat options menu")
        if (menuOpen.ok) {
            waitFor(300)
            val tapped = tapAnyBySelector(svc, menuLabels.map { Selector(contentDescriptionPartial = it) } +
                menuLabels.map { Selector(partialText = it) }, menuLabels.joinToString("/"))
            if (tapped.ok) return tapped
        }
        // Some info screens (contact/group info) are opened by tapping the chat header instead.
        return tapAnyBySelector(svc, menuLabels.map { Selector(contentDescriptionPartial = it) } +
            menuLabels.map { Selector(partialText = it) }, menuLabels.joinToString("/"))
    }

    private fun destructiveChatAction(svc: ZoyaAccessibilityService, target: String?, actionLabel: String, confirmed: Boolean): AutomationResult {
        if (!confirmed) {
            return AutomationResult(
                status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                errorCode = "CONFIRMATION_REQUIRED",
                errorMessage = "'$actionLabel' is destructive and needs user confirmation before it runs. Retry with confirmed=true once confirmed."
            )
        }
        val r = chatListLongPressThenMenu(svc, target, actionLabel)
        if (!r.ok) return r
        waitFor(300)
        return tapAnyBySelector(svc, listOf(Selector(partialText = actionLabel), Selector(partialText = "Delete"), Selector(partialText = "Clear")), "confirm $actionLabel")
    }

    private fun leaveGroup(svc: ZoyaAccessibilityService, confirmed: Boolean): AutomationResult {
        if (!confirmed) {
            return AutomationResult(
                status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                errorCode = "CONFIRMATION_REQUIRED",
                errorMessage = "Leaving a group is destructive and needs user confirmation. Retry with confirmed=true once confirmed."
            )
        }
        val info = openChatOptionsThen(svc, "Group info")
        if (!info.ok) return info
        waitFor(300)
        val exit = tapAnyBySelector(svc, listOf(Selector(partialText = "Exit group")), "exit group")
        if (!exit.ok) return exit
        waitFor(300)
        return tapAnyBySelector(svc, listOf(Selector(partialText = "Exit")), "confirm exit group")
    }

    // =====================================================================
    // Media
    // =====================================================================

    private fun openAttachMenu(svc: ZoyaAccessibilityService): AutomationResult =
        tapBySelector(svc, Selector(contentDescriptionPartial = "Attach"), "attach button")

    private fun sendMedia(svc: ZoyaAccessibilityService, target: String?, caption: String?, confirmed: Boolean): AutomationResult {
        if (!confirmed) {
            return AutomationResult(
                status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                errorCode = "CONFIRMATION_REQUIRED",
                errorMessage = "Sending media needs user confirmation before it runs. Retry with confirmed=true once confirmed."
            )
        }
        if (!target.isNullOrBlank()) {
            val opened = openChat(svc, target)
            if (!opened.ok) return opened
        }
        val attach = openAttachMenu(svc)
        if (!attach.ok) return attach
        waitFor(300)
        val gallery = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Gallery"), Selector(partialText = "Gallery"), Selector(partialText = "Photos")), "gallery option")
        if (!gallery.ok) return AutomationResult.failure("MEDIA_NOT_FOUND", "Could not open the media gallery picker.")
        waitFor(500)

        // "Latest photo/video" = first tile in the media picker grid (WhatsApp/Android sort newest-first).
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val firstTile = NodeFinder.findFirst(root, Selector(clickable = true, className = "ImageView"))
        root.recycle()
        if (firstTile == null) return AutomationResult.failure("MEDIA_NOT_FOUND", "No media found in the picker.")
        val select = tapNode(svc, firstTile)
        firstTile.recycle()
        if (!select.ok) return select
        waitFor(300)

        if (!caption.isNullOrBlank()) {
            val captionResult = typeIntoFocused(svc, caption)
            if (!captionResult.ok) debug("caption type failed, continuing without caption")
        }

        val send = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Send")), "send media")
        if (!send.ok) return AutomationResult.failure("SEND_FAILED", "Could not tap send on the media preview.")
        waitFor(500)
        return AutomationResult.success(message = "Media sent successfully.")
    }

    private fun sendDocument(svc: ZoyaAccessibilityService, target: String?, fileName: String?, confirmed: Boolean): AutomationResult {
        if (!confirmed) {
            return AutomationResult(
                status = com.zoya.ai.assistant.core.model.ResultStatus.BLOCKED,
                errorCode = "CONFIRMATION_REQUIRED",
                errorMessage = "Sending a document needs user confirmation before it runs. Retry with confirmed=true once confirmed."
            )
        }
        if (!target.isNullOrBlank()) {
            val opened = openChat(svc, target)
            if (!opened.ok) return opened
        }
        val attach = openAttachMenu(svc)
        if (!attach.ok) return attach
        waitFor(300)
        val docOption = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Document"), Selector(partialText = "Document")), "document option")
        if (!docOption.ok) return AutomationResult.failure("MEDIA_NOT_FOUND", "Could not open the document picker.")
        waitFor(500)

        if (!fileName.isNullOrBlank()) {
            val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
            val fileNode = NodeFinder.findFirst(root, Selector(partialText = fileName))
            root.recycle()
            if (fileNode == null) return AutomationResult.failure("MEDIA_NOT_FOUND", "No document matching '$fileName' found.")
            val select = tapNode(svc, fileNode)
            fileNode.recycle()
            if (!select.ok) return select
        }
        waitFor(500)
        val send = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Send")), "send document")
        if (!send.ok) return AutomationResult.failure("SEND_FAILED", "Could not tap send for the document.")
        return AutomationResult.success(message = "Document sent successfully.")
    }

    private fun openCamera(svc: ZoyaAccessibilityService): AutomationResult =
        tapBySelector(svc, Selector(contentDescriptionPartial = "Camera"), "camera button")

    // =====================================================================
    // Voice messages
    // =====================================================================

    private fun startVoiceMessage(svc: ZoyaAccessibilityService): AutomationResult =
        longPressBySelector(svc, Selector(contentDescriptionPartial = "Voice message"), "voice message mic")

    private fun stopAndSendVoiceMessage(svc: ZoyaAccessibilityService): AutomationResult =
        tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Send"), Selector(contentDescriptionPartial = "Lock")), "send/lock voice message")

    private fun cancelVoiceMessage(svc: ZoyaAccessibilityService): AutomationResult =
        tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Cancel")), "cancel voice message")

    // =====================================================================
    // Status / Updates
    // =====================================================================

    private fun openStatusViewer(svc: ZoyaAccessibilityService, target: String?): AutomationResult {
        val statusTab = tapBottomTab(svc, "Updates", "Status")
        if (!statusTab.ok) return statusTab
        waitFor(400)
        if (target.isNullOrBlank()) return AutomationResult.success(message = "Opened Updates tab.")
        return tapBySelector(svc, Selector(partialText = target), "status of '$target'")
    }

    private fun tapScreenFraction(svc: ZoyaAccessibilityService, xFrac: Double, yFrac: Double): AutomationResult {
        val gi = svc.gestureInjector
        return gi.dispatch(GestureInjector.GestureSpec(type = "tap", x = xFrac, y = yFrac, normalized = true))
    }

    private fun replyToStatus(svc: ZoyaAccessibilityService, reply: String?): AutomationResult {
        if (reply.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Reply text is required.")
        val field = requireRoot(svc)?.let { root ->
            val n = NodeFinder.findFirst(root, Selector(contentDescriptionPartial = "Reply"))
            root.recycle()
            n
        } ?: return AutomationResult.failure("ELEMENT_NOT_FOUND", "Status reply field not found.")
        val focus = tapNode(svc, field)
        field.recycle()
        if (!focus.ok) return focus
        val typed = typeIntoFocused(svc, reply)
        if (!typed.ok) return typed
        return tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Send")), "send status reply")
    }

    private fun createTextStatus(svc: ZoyaAccessibilityService, text: String?): AutomationResult {
        if (text.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Status text is required.")
        val statusTab = tapBottomTab(svc, "Updates", "Status")
        if (!statusTab.ok) return statusTab
        waitFor(300)
        val textStatus = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Add text status"), Selector(partialText = "Text")), "text status composer")
        if (!textStatus.ok) return textStatus
        waitFor(300)
        val typed = typeIntoFocused(svc, text)
        if (!typed.ok) return typed
        return AutomationResult.success(message = "Text status composed. Call publishStatus to send it.")
    }

    private fun createMediaStatus(svc: ZoyaAccessibilityService, kind: String): AutomationResult {
        val statusTab = tapBottomTab(svc, "Updates", "Status")
        if (!statusTab.ok) return statusTab
        waitFor(300)
        val addStatus = tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = "Add status"), Selector(contentDescriptionPartial = "Camera")), "add status")
        if (!addStatus.ok) return addStatus
        waitFor(400)
        return AutomationResult.success(message = "Opened camera/gallery for $kind status. Select $kind, add a caption if needed, then call publishStatus.")
    }

    // =====================================================================
    // Calls
    // =====================================================================

    private fun startCall(svc: ZoyaAccessibilityService, target: String?, video: Boolean): AutomationResult {
        if (!target.isNullOrBlank()) {
            val opened = openChat(svc, target)
            if (!opened.ok) return opened
        }
        val label = if (video) "Video call" else "Voice call"
        return tapAnyBySelector(svc, listOf(Selector(contentDescriptionPartial = label)), label)
    }

    // =====================================================================
    // Settings
    // =====================================================================

    private fun openSettings(svc: ZoyaAccessibilityService): AutomationResult {
        val moreOptions = tapBySelector(svc, Selector(contentDescriptionPartial = "More options"), "overflow menu")
        if (moreOptions.ok) {
            waitFor(300)
            val settingsTap = tapAnyBySelector(svc, listOf(Selector(partialText = "Settings")), "settings menu item")
            if (settingsTap.ok) return settingsTap
        }
        return tapBySelector(svc, Selector(contentDescriptionPartial = "Settings"), "settings tab")
    }

    /**
     * Discovers and taps a settings section by its visible label instead of
     * assuming a fixed menu layout, so it survives WhatsApp version changes.
     */
    private fun openSettingsSection(svc: ZoyaAccessibilityService, section: String?): AutomationResult {
        if (section.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Settings section name is required.")
        val opened = openSettings(svc)
        if (!opened.ok) return opened
        waitFor(400)
        return scrollUntilFound(svc, section, thenTap = true)
    }

    // =====================================================================
    // Generic gestures (raw, coordinate-or-selector based)
    // =====================================================================

    private fun genericTap(svc: ZoyaAccessibilityService, args: Map<String, Any?>): AutomationResult {
        val selector = selectorFrom(args)
        if (selector != null) return tapBySelector(svc, selector, "element")
        val x = numArg(args, "x")
        val y = numArg(args, "y")
        if (x == null || y == null) return AutomationResult.failure("ACTION_FAILED", "Provide either a selector or x/y for tap.")
        return svc.gestureInjector.dispatch(GestureInjector.GestureSpec(type = "tap", x = x, y = y, normalized = isNormalized(x, y)))
    }

    private fun genericLongPress(svc: ZoyaAccessibilityService, args: Map<String, Any?>): AutomationResult {
        val selector = selectorFrom(args)
        if (selector != null) return longPressBySelector(svc, selector, "element")
        val x = numArg(args, "x")
        val y = numArg(args, "y")
        if (x == null || y == null) return AutomationResult.failure("ACTION_FAILED", "Provide either a selector or x/y for longPress.")
        return svc.gestureInjector.dispatch(GestureInjector.GestureSpec(type = "longpress", x = x, y = y, normalized = isNormalized(x, y)))
    }

    private fun genericDoubleTap(svc: ZoyaAccessibilityService, args: Map<String, Any?>): AutomationResult {
        val x = numArg(args, "x") ?: 0.5
        val y = numArg(args, "y") ?: 0.5
        val first = svc.gestureInjector.dispatch(GestureInjector.GestureSpec(type = "tap", x = x, y = y, normalized = isNormalized(x, y)))
        if (!first.ok) return first
        waitFor(120)
        return svc.gestureInjector.dispatch(GestureInjector.GestureSpec(type = "tap", x = x, y = y, normalized = isNormalized(x, y)))
    }

    private fun genericSwipe(svc: ZoyaAccessibilityService, args: Map<String, Any?>): AutomationResult {
        val fromX = numArg(args, "fromX") ?: 0.5
        val fromY = numArg(args, "fromY") ?: 0.8
        val toX = numArg(args, "toX") ?: 0.5
        val toY = numArg(args, "toY") ?: 0.2
        return svc.gestureInjector.dispatch(
            GestureInjector.GestureSpec(type = "swipe", fromX = fromX, fromY = fromY, toX = toX, toY = toY, normalized = true)
        )
    }

    private fun genericScroll(svc: ZoyaAccessibilityService, args: Map<String, Any?>): AutomationResult {
        val direction = (args["direction"] as? String)?.lowercase() ?: "down"
        val (fromY, toY) = if (direction == "up") 0.3 to 0.75 else 0.75 to 0.3
        return svc.gestureInjector.dispatch(
            GestureInjector.GestureSpec(type = "swipe", fromX = 0.5, fromY = fromY, toX = 0.5, toY = toY, normalized = true)
        )
    }

    private fun scrollUntilFound(svc: ZoyaAccessibilityService, target: String?, thenTap: Boolean = false, maxScrolls: Int = 8): AutomationResult {
        if (target.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Target text is required.")
        repeat(maxScrolls) {
            val root = requireRoot(svc)
            if (root != null) {
                val node = NodeFinder.findFirst(root, Selector(partialText = target))
                root.recycle()
                if (node != null) {
                    val result = if (thenTap) tapNode(svc, node) else AutomationResult.success()
                    node.recycle()
                    return result
                }
            }
            val scrolled = genericScroll(svc, mapOf("direction" to "down"))
            if (!scrolled.ok) return@repeat
            waitFor(350)
        }
        return AutomationResult.failure("ELEMENT_NOT_FOUND", "Could not find '$target' after scrolling.")
    }

    private fun scrollExtreme(svc: ZoyaAccessibilityService, toTop: Boolean, maxScrolls: Int = 15): AutomationResult {
        var lastSignature = ""
        repeat(maxScrolls) {
            val root = requireRoot(svc)
            val signature = root?.let { r ->
                val texts = StringBuilder()
                NodeFinder.walk(r) { n, _ -> texts.append(n.text).append('|'); null }
                texts.toString()
            } ?: ""
            root?.recycle()
            if (signature == lastSignature && signature.isNotEmpty()) {
                return AutomationResult.success(message = if (toTop) "Reached top." else "Reached bottom.")
            }
            lastSignature = signature
            val scrolled = genericScroll(svc, mapOf("direction" to if (toTop) "up" else "down"))
            if (!scrolled.ok) return scrolled
            waitFor(300)
        }
        return AutomationResult.success(message = "Stopped after max scroll attempts.")
    }

    private fun findElementResult(svc: ZoyaAccessibilityService, args: Map<String, Any?>): AutomationResult {
        val selector = selectorFrom(args) ?: return AutomationResult.failure("ACTION_FAILED", "A selector is required.")
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val node = NodeFinder.findFirst(root, selector)
        root.recycle()
        return if (node != null) {
            val info = NodeFinder.toNodeInfo(node, "0", includeChildren = false)
            node.recycle()
            AutomationResult.success(info.toJson())
        } else {
            AutomationResult.failure("ELEMENT_NOT_FOUND", "No element matched the given selector.")
        }
    }

    private fun waitForElementResult(svc: ZoyaAccessibilityService, args: Map<String, Any?>, timeoutMs: Long): AutomationResult {
        val selector = selectorFrom(args) ?: return AutomationResult.failure("ACTION_FAILED", "A selector is required.")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtMost(30_000L)
        while (System.currentTimeMillis() < deadline) {
            val root = requireRoot(svc)
            if (root != null) {
                val node = NodeFinder.findFirst(root, selector)
                root.recycle()
                if (node != null) {
                    node.recycle()
                    return AutomationResult.success()
                }
            }
            waitFor(250)
        }
        return AutomationResult.timeout("Element did not appear within $timeoutMs ms.")
    }

    // =====================================================================
    // Low-level helpers
    // =====================================================================

    private fun requireRoot(svc: ZoyaAccessibilityService): AccessibilityNodeInfo? = svc.screenContext.getRoot()

    private fun tapNode(svc: ZoyaAccessibilityService, node: AccessibilityNodeInfo): AutomationResult {
        if (node.isClickable) {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return AutomationResult.success()
        }
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return AutomationResult.failure("ACTION_FAILED", "Element has no visible bounds to tap.")
        return svc.gestureInjector.dispatch(
            GestureInjector.GestureSpec(
                type = "tap",
                x = rect.centerX().toDouble(),
                y = rect.centerY().toDouble(),
                normalized = false
            )
        )
    }

    private fun longPressNode(svc: ZoyaAccessibilityService, node: AccessibilityNodeInfo): AutomationResult {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return AutomationResult.failure("ACTION_FAILED", "Element has no visible bounds to long-press.")
        return svc.gestureInjector.dispatch(
            GestureInjector.GestureSpec(
                type = "longpress",
                x = rect.centerX().toDouble(),
                y = rect.centerY().toDouble(),
                normalized = false
            )
        )
    }

    private fun tapBySelector(svc: ZoyaAccessibilityService, selector: Selector, label: String): AutomationResult {
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val node = NodeFinder.findFirst(root, selector)
        root.recycle()
        if (node == null) return AutomationResult.failure("ELEMENT_NOT_FOUND", "Could not find $label.")
        val r = tapNode(svc, node)
        node.recycle()
        return r
    }

    private fun tapAnyBySelector(svc: ZoyaAccessibilityService, selectors: List<Selector>, label: String): AutomationResult {
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        try {
            for (sel in selectors) {
                val node = NodeFinder.findFirst(root, sel)
                if (node != null) {
                    val r = tapNode(svc, node)
                    node.recycle()
                    return r
                }
            }
        } finally {
            root.recycle()
        }
        return AutomationResult.failure("ELEMENT_NOT_FOUND", "Could not find $label.")
    }

    private fun longPressBySelector(svc: ZoyaAccessibilityService, selector: Selector, label: String): AutomationResult {
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val node = NodeFinder.findFirst(root, selector)
        root.recycle()
        if (node == null) return AutomationResult.failure("ELEMENT_NOT_FOUND", "Could not find $label.")
        val r = longPressNode(svc, node)
        node.recycle()
        return r
    }

    private fun typeIntoFocused(svc: ZoyaAccessibilityService, text: String?): AutomationResult {
        if (text.isNullOrBlank()) return AutomationResult.failure("ACTION_FAILED", "Text is required.")
        val root = requireRoot(svc) ?: return AutomationResult.blocked("UI_STATE_UNKNOWN", "No active window.")
        val focused = NodeFinder.findFirst(root, Selector(editable = true))
        root.recycle()
        if (focused == null) return AutomationResult.failure("ELEMENT_NOT_FOUND", "No focused/editable text field found.")
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return if (ok) AutomationResult.success() else AutomationResult.failure("ACTION_FAILED", "Failed to type text into the focused field.")
    }

    // ---- small utility predicates for screen-state detection ----

    private fun hasText(root: AccessibilityNodeInfo, text: String): Boolean =
        NodeFinder.findFirst(root, Selector(partialText = text))?.also { it.recycle() } != null

    private fun hasContentDescPartial(root: AccessibilityNodeInfo, text: String): Boolean =
        NodeFinder.findFirst(root, Selector(contentDescriptionPartial = text))?.also { it.recycle() } != null

    private fun hasResourceId(root: AccessibilityNodeInfo, id: String): Boolean =
        NodeFinder.findFirst(root, Selector(resourceId = id))?.also { it.recycle() } != null

    private fun isDialogPresent(root: AccessibilityNodeInfo): Boolean =
        NodeFinder.findFirst(root, Selector(className = "Dialog"))?.also { it.recycle() } != null

    private fun waitFor(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun textArg(args: Map<String, Any?>, key: String): String? = args[key] as? String

    private fun numArg(args: Map<String, Any?>, key: String): Double? = when (val v = args[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun isNormalized(x: Double, y: Double): Boolean = x in 0.0..1.0 && y in 0.0..1.0

    private fun selectorFrom(args: Map<String, Any?>): Selector? {
        val exact = textArg(args, "exactText")
        val partial = textArg(args, "partialText") ?: textArg(args, "text")
        val cd = textArg(args, "contentDescription")
        val cdPartial = textArg(args, "contentDescriptionPartial")
        val resId = textArg(args, "resourceId")
        val cls = textArg(args, "className")
        if (exact == null && partial == null && cd == null && cdPartial == null && resId == null && cls == null) return null
        return Selector(
            exactText = exact,
            partialText = partial,
            contentDescription = cd,
            contentDescriptionPartial = cdPartial,
            resourceId = resId,
            className = cls
        )
    }
}
