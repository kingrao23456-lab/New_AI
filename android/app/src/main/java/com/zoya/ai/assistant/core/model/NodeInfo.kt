package com.zoya.ai.assistant.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A serializable snapshot of an accessibility node, used to expose the UI
 * tree to the web layer and to drive semantic automation.
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
        put("width", width)
        put("height", height)
        put("centerX", centerX)
        put("centerY", centerY)
    }

    companion object {
        fun fromRect(rect: android.graphics.Rect): Bounds =
            Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

data class NodeInfo(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val packageName: String?,
    val viewIdResourceName: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val isPassword: Boolean,
    val isRoot: Boolean,
    val visibleToUser: Boolean,
    val bounds: Bounds?,
    val children: List<NodeInfo> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("nodeId", nodeId)
        obj.put("text", text ?: JSONObject.NULL)
        obj.put("contentDescription", contentDescription ?: JSONObject.NULL)
        obj.put("resourceId", resourceId ?: JSONObject.NULL)
        obj.put("className", className ?: JSONObject.NULL)
        obj.put("packageName", packageName ?: JSONObject.NULL)
        obj.put("clickable", clickable)
        obj.put("enabled", enabled)
        obj.put("selected", selected)
        obj.put("focused", focused)
        obj.put("editable", editable)
        obj.put("scrollable", scrollable)
        obj.put("longClickable", longClickable)
        obj.put("checkable", checkable)
        obj.put("checked", checked)
        obj.put("isPassword", isPassword)
        obj.put("isRoot", isRoot)
        obj.put("visibleToUser", visibleToUser)
        obj.put("bounds", bounds?.toJson() ?: JSONObject.NULL)
        if (children.isNotEmpty()) {
            val arr = JSONArray()
            children.forEach { arr.put(it.toJson()) }
            obj.put("children", arr)
        }
        return obj
    }

    fun toJsonTree(depth: Int = 0): JSONObject {
        val obj = toJson()
        obj.put("depth", depth)
        if (children.isNotEmpty()) {
            val arr = JSONArray()
            children.forEach { arr.put(it.toJsonTree(depth + 1)) }
            obj.put("children", arr)
        }
        return obj
    }
}
