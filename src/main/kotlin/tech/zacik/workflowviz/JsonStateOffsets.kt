package tech.zacik.workflowviz

/**
 * Maps between editor offsets and state names by simple text search on the JSON.
 * Good enough for the MVP (no JSON PSI dependency); harden with PSI later if a
 * state name ever collides with a function/event name or appears out of order.
 */
object JsonStateOffsets {

    /** Offset of the `"name": "<state>"` value declaration, or -1. */
    fun offsetOfState(text: String, name: String): Int {
        val m = nameDeclRegex(name).find(text) ?: return -1
        // Put the caret on the name value (inside the quotes), not the key.
        return m.range.first + m.value.lastIndexOf(name)
    }

    /**
     * Which state the caret is "in": the state whose `"name"` declaration is the
     * last one at or before the caret offset.
     */
    fun enclosingState(text: String, caret: Int, stateNames: List<String>): String? {
        var best: String? = null
        var bestOffset = -1
        for (name in stateNames) {
            val off = offsetOfState(text, name)
            if (off in 0..caret && off > bestOffset) {
                bestOffset = off
                best = name
            }
        }
        return best
    }

    private fun nameDeclRegex(name: String) =
        Regex("\"name\"\\s*:\\s*\"" + Regex.escape(name) + "\"")
}
