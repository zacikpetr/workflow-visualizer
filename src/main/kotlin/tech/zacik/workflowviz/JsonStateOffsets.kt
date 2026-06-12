package tech.zacik.workflowviz

/**
 * Maps between editor offsets and state names by simple text search on the JSON.
 * Good enough for the MVP (no JSON PSI dependency); harden with PSI later if a
 * state name ever collides with a function/event name or appears out of order.
 */
object JsonStateOffsets {

    /** Any `"name": "<value>"` declaration; group 1 = the raw value. */
    private val NAME_DECL = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Offset of the `"name": "<state>"` value declaration, or -1. */
    fun offsetOfState(text: String, name: String): Int {
        val m = nameDeclRegex(name).find(text) ?: return -1
        // Put the caret on the name value (inside the quotes), not the key.
        return m.range.first + m.value.lastIndexOf(name)
    }

    /**
     * First `"name": "<state>"` declaration offset per state, sorted by offset.
     * One linear scan — callers cache the result per document version and feed
     * it to [enclosingState] on every caret move, instead of re-scanning the
     * whole text per state name per move.
     */
    fun nameOffsets(text: String, stateNames: List<String>): List<Pair<Int, String>> {
        val wanted = stateNames.toHashSet()
        val seen = HashSet<String>()
        val out = mutableListOf<Pair<Int, String>>()
        for (m in NAME_DECL.findAll(text)) {
            val value = m.groupValues[1]
            if (value in wanted && seen.add(value)) {
                out += m.groups[1]!!.range.first to value
            }
        }
        return out // findAll yields matches in order → already offset-sorted
    }

    /**
     * Which state the caret is "in": the state whose `"name"` declaration is the
     * last one at or before the caret offset. [nameOffsets] comes from
     * [JsonStateOffsets.nameOffsets].
     */
    fun enclosingState(nameOffsets: List<Pair<Int, String>>, caret: Int): String? =
        nameOffsets.lastOrNull { it.first <= caret }?.second

    private fun nameDeclRegex(name: String) =
        Regex("\"name\"\\s*:\\s*\"" + Regex.escape(name) + "\"")
}
