package tech.zacik.workflowviz

import com.google.gson.Gson

/** Parse a .sw.json string into nested Maps/Lists (Gson). */
object WorkflowJson {
    private val gson = Gson()

    /** Returns the workflow map, or null if the text isn't a workflow (no states/start). */
    fun parse(text: String): Map<*, *>? {
        if (text.isBlank()) return null
        return try {
            val map = gson.fromJson(text, Map::class.java) ?: return null
            if (map["states"] is List<*>) map else null
        } catch (e: Exception) {
            null // invalid/partial JSON while typing — ignore, keep last good render
        }
    }
}
