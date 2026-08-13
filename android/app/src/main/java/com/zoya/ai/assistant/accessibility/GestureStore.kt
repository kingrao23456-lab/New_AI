package com.zoya.ai.assistant.accessibility

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent store for recorded gestures. Gestures are kept as JSON files in
 * app-private storage and support save / rename / edit / duplicate / delete /
 * import / export.
 */
class GestureStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "gestures").apply { mkdirs() }

    fun save(gesture: GestureRecorder.RecordedGesture): Boolean {
        return runCatching {
            val file = File(dir, "${gesture.id}.json")
            file.writeText(gesture.toJson().toString())
            true
        }.getOrDefault(false)
    }

    fun delete(id: String): Boolean {
        val file = File(dir, "$id.json")
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun get(id: String): GestureRecorder.RecordedGesture? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching {
            GestureRecorder.RecordedGesture.fromJson(JSONObject(file.readText()))
        }.getOrNull()
    }

    fun list(): List<GestureRecorder.RecordedGesture> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                GestureRecorder.RecordedGesture.fromJson(JSONObject(f.readText()))
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun rename(id: String, newName: String): Boolean {
        val gesture = get(id) ?: return false
        val updated = gesture.copy(name = newName)
        return save(updated)
    }

    fun duplicate(id: String, newName: String? = null): GestureRecorder.RecordedGesture? {
        val gesture = get(id) ?: return null
        val copy = gesture.copy(
            id = "gesture_" + System.currentTimeMillis(),
            name = newName ?: "${gesture.name} (copy)"
        )
        return if (save(copy)) copy else null
    }

    fun importJson(raw: String): GestureRecorder.RecordedGesture? {
        return runCatching {
            val json = JSONObject(raw)
            val gesture = GestureRecorder.RecordedGesture.fromJson(json)
            val renamed = gesture.copy(id = gesture.id.takeIf { it.isNotBlank() } ?: "gesture_" + System.currentTimeMillis())
            if (save(renamed)) renamed else null
        }.getOrNull()
    }

    fun importBundle(raw: String): List<GestureRecorder.RecordedGesture> {
        return runCatching {
            val arr = JSONArray(raw)
            val imported = mutableListOf<GestureRecorder.RecordedGesture>()
            for (i in 0 until arr.length()) {
                val g = GestureRecorder.RecordedGesture.fromJson(arr.getJSONObject(i))
                if (save(g)) imported.add(g)
            }
            imported
        }.getOrDefault(emptyList())
    }

    fun exportJson(id: String): String? {
        return get(id)?.toJson()?.toString()
    }

    fun exportBundle(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { id ->
            get(id)?.let { arr.put(it.toJson()) }
        }
        return arr.toString()
    }
}
