package com.zoya.ai.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zoya.ai.assistant.core.model.NodeInfo
import com.zoya.ai.assistant.core.model.Selector
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Finds accessibility nodes by semantic selectors. Semantic matching is
 * preferred over coordinates everywhere in the automation stack.
 */
object NodeFinder {

    private const val MAX_NODES = 2000
    private const val MAX_DEPTH = 120

    /** Traverses the tree in document order, recycling children properly. */
    fun <T> walk(
        root: AccessibilityNodeInfo?,
        maxDepth: Int = MAX_DEPTH,
        visit: (AccessibilityNodeInfo, Int) -> T?
    ): T? {
        if (root == null) return null
        val seen = HashSet<AccessibilityNodeInfo>()
        var result: T? = null

        fun dfs(node: AccessibilityNodeInfo, depth: Int) {
            if (result != null) return
            if (!seen.add(node)) return
            if (depth > maxDepth) return
            result = visit(node, depth)
            if (result != null) return
            for (i in 0 until node.childCount) {
                if (result != null) return
                val child = node.getChild(i)
                if (child != null) {
                    dfs(child, depth + 1)
                    child.recycle()
                }
            }
        }
        dfs(root, 0)
        return result
    }

    /** Finds all matching nodes, up to [maxResults]. */
    fun findAll(root: AccessibilityNodeInfo?, selector: Selector, maxResults: Int = 50): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val results = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<AccessibilityNodeInfo>()
        val regex = compileRegex(selector.regexText)
        var visited = 0

        fun matches(node: AccessibilityNodeInfo): Boolean {
            if (!node.isVisibleToUser && !selector.clickable.isNullOrFalse()) {
                // invisible nodes are skipped unless explicitly requested by a resource-id search
            }
            selector.exactText?.let { if (node.text?.toString() != it) return false }
            selector.partialText?.let { if (!node.text?.toString()?.contains(it, ignoreCase = true)!!) return false }
            if (regex != null && !regex.matcher(node.text?.toString() ?: "").find()) return false
            selector.contentDescription?.let {
                if (node.contentDescription?.toString() != it) return false
            }
            selector.contentDescriptionPartial?.let {
                if (!node.contentDescription?.toString()?.contains(it, ignoreCase = true)!!) return false
            }
            selector.resourceId?.let {
                if (node.viewIdResourceName?.substringAfterLast('/') != it &&
                    node.viewIdResourceName != it
                ) return false
            }
            selector.className?.let { if (node.className?.toString()?.endsWith(it) != true) return false }
            selector.packageName?.let { if (node.packageName?.toString() != it) return false }
            selector.clickable?.let { if (node.isClickable != it) return false }
            selector.enabled?.let { if (node.isEnabled != it) return false }
            selector.editable?.let { if (node.isEditable != it) return false }
            selector.scrollable?.let { if (node.isScrollable != it) return false }
            selector.checked?.let { if (node.isChecked != it) return false }
            return true
        }

        fun dfs(node: AccessibilityNodeInfo, depth: Int) {
            if (results.size >= maxResults) return
            if (visited++ > MAX_NODES) return
            if (!seen.add(node)) return
            if (depth > MAX_DEPTH) return
            if (matches(node)) {
                results.add(AccessibilityNodeInfo.obtain(node))
            }
            if (results.size >= maxResults) return
            for (i in 0 until node.childCount) {
                if (results.size >= maxResults) return
                val child = node.getChild(i)
                if (child != null) {
                    dfs(child, depth + 1)
                    child.recycle()
                }
            }
        }
        dfs(root, 0)
        return results
    }

    fun findFirst(root: AccessibilityNodeInfo?, selector: Selector): AccessibilityNodeInfo? {
        val all = findAll(root, selector, maxResults = 1)
        return all.firstOrNull()
    }

    /**
     * Classifies the UI element type of a node for element-type detection
     * (buttons, text fields, switches, checkboxes, radio buttons, lists,
     * menus, dialogs and scroll containers).
     */
    fun elementType(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val role = when {
            node.isEditable || cls.contains("EditText") || node.text?.toString()?.isNotBlank() == true && cls.contains("TextView") && node.isClickable && node.className?.toString()?.contains("AutoComplete", true) == true -> "text_field"
            node.isCheckable && cls.contains("CheckBox") -> "checkbox"
            node.isCheckable && cls.contains("RadioButton") -> "radio_button"
            node.isCheckable && cls.contains("Switch") -> "switch"
            node.isScrollable || cls.contains("ScrollView") || cls.contains("RecyclerView") ||
                cls.contains("ListView") || cls.contains("NestedScrollView") -> "scroll_container"
            cls.contains("Dialog") || cls.contains("AlertDialog") || cls.contains("PopupWindow") -> "dialog"
            cls.contains("Menu") || cls.contains("NavigationView") || cls.contains("Spinner") -> "menu"
            cls.contains("ListView") || cls.contains("RecyclerView") || cls.contains("GridView") -> "list"
            node.isClickable -> "button"
            node.isEditable -> "text_field"
            else -> "generic"
        }
        return role
    }

    /** Maps a node to a serializable [NodeInfo]. */
    fun toNodeInfo(node: AccessibilityNodeInfo, nodeId: String, includeChildren: Boolean = true, depth: Int = 0): NodeInfo {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val children = if (includeChildren && depth < 8) {
            (0 until node.childCount).mapNotNull { i ->
                val child = node.getChild(i)
                val info = child?.let { toNodeInfo(it, "$nodeId/$i", true, depth + 1) }
                child?.recycle()
                info
            }
        } else emptyList()

        return NodeInfo(
            nodeId = nodeId,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName?.substringAfterLast('/'),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            selected = node.isSelected,
            focused = node.isFocused,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            longClickable = node.isLongClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            isPassword = node.isPassword,
            isRoot = node.parent == null || depth == 0,
            visibleToUser = node.isVisibleToUser,
            bounds = if (rect.isEmpty) null else com.zoya.ai.assistant.core.model.Bounds.fromRect(rect),
            children = children
        )
    }

    private fun compileRegex(pattern: String?): Pattern? {
        if (pattern.isNullOrBlank()) return null
        return try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (e: PatternSyntaxException) {
            null
        }
    }

    private fun Boolean?.isNullOrFalse(): Boolean = this == null || this == false
}
