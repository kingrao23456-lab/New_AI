package com.zoya.ai.assistant.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * Performs semantic accessibility actions on nodes. Semantic actions are
 * always preferred over raw coordinate injection.
 */
object SemanticActions {

    fun click(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun focus(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    fun clearFocus(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
    }

    private fun setTextArgs(text: String): Bundle {
        return Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
    }

    fun clearText(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(""))
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(text))
    }

    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun scrollTo(node: AccessibilityNodeInfo, row: Int): Boolean {
        return node.performAction(
            AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, row)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
            }
        )
    }

    fun select(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
    }

    fun toggle(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun setChecked(node: AccessibilityNodeInfo, checked: Boolean): Boolean {
        if (node.isChecked == checked) return true
        // Android has no dedicated CHECK/UNCHECK action; a click toggles state.
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun dismiss(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
    }

    fun moveWindow(node: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        return node.performAction(
            AccessibilityAction.ACTION_MOVE_WINDOW.id,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_X, x)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_Y, y)
            }
        )
    }

    /** Requests a custom accessibility action by id if the node supports it. */
    fun customAction(node: AccessibilityNodeInfo, actionId: Int, args: Bundle? = null): Boolean {
        return node.performAction(actionId, args)
    }

    /** Returns the list of supported custom action ids for the node. */
    fun customActionIds(node: AccessibilityNodeInfo): List<Int> {
        return node.actionList?.mapNotNull { it.id } ?: emptyList()
    }

    /** Global actions that can be performed via the service (BACK, HOME, etc). */
    fun globalAction(service: android.accessibilityservice.AccessibilityService, action: Int): Boolean {
        return service.performGlobalAction(action)
    }
}
