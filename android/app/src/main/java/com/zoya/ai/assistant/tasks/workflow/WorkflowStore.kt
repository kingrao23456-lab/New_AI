package com.zoya.ai.assistant.tasks.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent workflow store with version history. Every save of an existing
 * workflow bumps the version (when content differs) and previous versions
 * remain restorable. The raw workflow JSON is kept verbatim so nothing is
 * lost in round-trips. All data is local — offline-first.
 */
class WorkflowStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("zoya_workflows", Context.MODE_PRIVATE)

    /** Returns the latest version summary of every saved workflow. */
    fun allWorkflows(): List<WorkflowSummary> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { WorkflowSummary.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): WorkflowSummary? = allWorkflows().firstOrNull { it.id == id }

    /**
     * Saves a workflow from its raw JSON definition. Returns the new version
     * number. Version increments only when the normalized content changed.
     */
    fun save(rawWorkflow: String): Int {
        val workflow = runCatching { WorkflowParser.parse(rawWorkflow) }.getOrElse { return 1 }
        val summaries = allWorkflows().toMutableList()
        val existing = summaries.firstOrNull { it.id == workflow.id }

        val currentRaw = latestRaw(workflow.id)
        val version = if (existing != null && currentRaw != null) {
            if (currentRaw == rawWorkflow) existing.version else existing.version + 1
        } else {
            1
        }

        val record = JSONObject()
        record.put("version", version)
        record.put("updatedAt", System.currentTimeMillis())
        record.put("raw", rawWorkflow)

        val versions = allVersions(workflow.id).toMutableList()
        versions.add(record)
        while (versions.size > MAX_VERSIONS) versions.removeAt(0)
        prefs.edit().putString(versionKey(workflow.id), JSONArray().apply {
            versions.forEach { put(it) }
        }.toString()).apply()

        summaries.removeAll { it.id == workflow.id }
        summaries.add(
            WorkflowSummary(
                id = workflow.id,
                name = workflow.name,
                version = version,
                updatedAt = System.currentTimeMillis()
            )
        )
        summaries.sortByDescending { it.updatedAt }
        prefs.edit().putString(KEY_INDEX, JSONArray().apply {
            summaries.forEach { put(it.toJson()) }
        }.toString()).apply()

        return version
    }

    /** Returns all saved versions of a workflow, newest last. */
    fun allVersions(id: String): List<JSONObject> {
        val raw = prefs.getString(versionKey(id), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    /** The latest raw JSON for a workflow, or null. */
    fun latestRaw(id: String): String? = allVersions(id).lastOrNull()?.optString("raw")

    /** Restores a specific version as the current definition. */
    fun restoreVersion(id: String, version: Int): Boolean {
        val record = allVersions(id).firstOrNull { it.optInt("version") == version } ?: return false
        val raw = record.optString("raw")
        return try {
            val workflow = WorkflowParser.parse(raw)
            val summaries = allWorkflows().toMutableList()
            summaries.removeAll { it.id == id }
            summaries.add(WorkflowSummary(id, workflow.name, version, System.currentTimeMillis()))
            prefs.edit().putString(KEY_INDEX, JSONArray().apply {
                summaries.forEach { put(it.toJson()) }
            }.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(id: String): Boolean {
        val summaries = allWorkflows().filterNot { it.id == id }
        prefs.edit().putString(KEY_INDEX, JSONArray().apply {
            summaries.forEach { put(it.toJson()) }
        }.toString()).apply()
        prefs.edit().remove(versionKey(id)).apply()
        return true
    }

    private fun versionKey(id: String): String = "$KEY_VERSIONS_PREFIX$id"

    companion object {
        private const val KEY_INDEX = "zoya_workflow_index"
        private const val KEY_VERSIONS_PREFIX = "zoya_workflow_versions_"
        private const val MAX_VERSIONS = 20
    }
}

data class WorkflowSummary(
    val id: String,
    val name: String,
    val version: Int,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): WorkflowSummary = WorkflowSummary(
            id = json.optString("id"),
            name = json.optString("name", "Untitled Workflow"),
            version = json.optInt("version", 1),
            updatedAt = json.optLong("updatedAt", 0)
        )
    }
}
