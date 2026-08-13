package com.zoya.ai.assistant.core.engine

import android.content.Context
import com.zoya.ai.assistant.core.security.SecretsRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Real-time automation log store. Every automation lifecycle event (task start,
 * app detected, target found, action selected, execution, verification, retry,
 * recovery, completion, failure, stop) is appended to an in-memory ring buffer
 * AND persisted to SharedPreferences for later inspection.
 *
 * All text is redacted via [SecretsRedactor] so passwords, OTPs and tokens are
 * never stored or broadcast. Listeners (used by the bridge to push live events
 * to the web layer) are invoked on the calling thread.
 */
class AutomationLogStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("zoya_logs", Context.MODE_PRIVATE)

    /** Event listener signature. */
    fun interface Listener {
        fun onLogEntry(entry: JSONObject)
    }

    private val listeners = ConcurrentLinkedQueue<Listener>()

    fun addListener(listener: Listener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * Records a lifecycle event. [phase] is one of:
     * task_start / current_app / target_found / target_not_found / action_start /
     * action_end / verify / retry / recovery / complete / failure / cancel /
     * system / info.
     */
    fun log(
        phase: String,
        command: String? = null,
        detail: String? = null,
        data: JSONObject? = null,
        level: String = "info"
    ) {
        val entry = JSONObject()
        entry.put("ts", System.currentTimeMillis())
        entry.put("phase", phase)
        entry.put("level", level)
        command?.let { entry.put("command", it) }
        detail?.let { entry.put("detail", SecretsRedactor.redactText(it)) }
        data?.let { entry.put("data", SecretsRedactor.redactJson(it)) }

        buffer.add(entry)
        if (buffer.size > MAX_RING_SIZE) buffer.removeFirstOrNull()
        persistRecent(entry)

        listeners.forEach { l -> runCatching { l.onLogEntry(entry) } }
    }

    fun recent(limit: Int = 200): List<JSONObject> {
        val list = buffer.toList().takeLast(limit)
        if (list.isNotEmpty()) return list
        return loadPersisted(limit)
    }

    fun clear() {
        buffer.clear()
        prefs.edit().remove(KEY_RECENT).apply()
    }

    private fun persistRecent(entry: JSONObject) {
        val current = runCatching { JSONArray(prefs.getString(KEY_RECENT, "[]")) }.getOrDefault(JSONArray())
        current.put(entry)
        while (current.length() > PERSISTED_LIMIT) current.remove(0)
        prefs.edit().putString(KEY_RECENT, current.toString()).apply()
    }

    private fun loadPersisted(limit: Int): List<JSONObject> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_RECENT, "[]")) }.getOrDefault(JSONArray())
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            runCatching { out.add(arr.getJSONObject(i)) }
        }
        return out.takeLast(limit)
    }

    private val buffer = ArrayDeque<JSONObject>()

    companion object {
        private const val KEY_RECENT = "zoya_recent_logs"
        private const val MAX_RING_SIZE = 500
        private const val PERSISTED_LIMIT = 300
    }
}
