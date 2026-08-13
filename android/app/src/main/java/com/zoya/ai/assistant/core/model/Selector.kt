package com.zoya.ai.assistant.core.model

import org.json.JSONObject

/**
 * Selector used to find a target node. Selectors can be combined so the
 * first matching node (in document order) that satisfies ALL criteria wins.
 */
data class Selector(
    val exactText: String? = null,
    val partialText: String? = null,
    val regexText: String? = null,
    val contentDescription: String? = null,
    val contentDescriptionPartial: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val checked: Boolean? = null,
    val index: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        exactText?.let { put("exactText", it) }
        partialText?.let { put("partialText", it) }
        regexText?.let { put("regexText", it) }
        contentDescription?.let { put("contentDescription", it) }
        contentDescriptionPartial?.let { put("contentDescriptionPartial", it) }
        resourceId?.let { put("resourceId", it) }
        className?.let { put("className", it) }
        packageName?.let { put("packageName", it) }
        clickable?.let { put("clickable", it) }
        enabled?.let { put("enabled", it) }
        editable?.let { put("editable", it) }
        scrollable?.let { put("scrollable", it) }
        checked?.let { put("checked", it) }
        put("index", index)
    }

    companion object {
        fun fromJson(json: JSONObject): Selector = Selector(
            exactText = json.optStringOrNull("exactText"),
            partialText = json.optStringOrNull("partialText"),
            regexText = json.optStringOrNull("regexText"),
            contentDescription = json.optStringOrNull("contentDescription"),
            contentDescriptionPartial = json.optStringOrNull("contentDescriptionPartial"),
            resourceId = json.optStringOrNull("resourceId"),
            className = json.optStringOrNull("className"),
            packageName = json.optStringOrNull("packageName"),
            clickable = json.optBooleanOrNull("clickable"),
            enabled = json.optBooleanOrNull("enabled"),
            editable = json.optBooleanOrNull("editable"),
            scrollable = json.optBooleanOrNull("scrollable"),
            checked = json.optBooleanOrNull("checked"),
            index = json.optInt("index", 0)
        )
    }
}

fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return v?.toString()
}

fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}
