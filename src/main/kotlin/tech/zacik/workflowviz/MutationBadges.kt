package tech.zacik.workflowviz

/**
 * Per-state mutation badges driven by parsing `expression`-typed functions.
 *
 * Many Serverless Workflow processes track runtime state in jq-mutated fields
 * (commonly `state`, `phase`, `continuationPoint`, `stateDetail`, but any
 * field name works — they're configurable). A `type: "expression"` function
 * whose `operation` is e.g. `{ state: "ACTIVE", phase: "X" }` mutates those
 * fields for every state whose `actions[]` calls it.
 *
 * This module discovers those mutations purely from the JSON — no regex on
 * action names, no project-specific config beyond "which fields to surface".
 * Dynamic mutations (`{ state: .stateBeforeError }`) are intentionally
 * **skipped** rather than rendered as `state=←`: we can't show the actual
 * value, and a non-deterministic badge would mis-suggest "this state always
 * sets X" when it just restores prior state.
 */
object MutationBadges {

    /** Per-state map of `field -> literal value`, for fields listed in [interestedFields]. */
    fun compute(workflow: Map<*, *>, interestedFields: Set<String>): Map<String, Map<String, String>> {
        if (interestedFields.isEmpty()) return emptyMap()
        val functionMutations = parseFunctions(workflow, interestedFields)
        if (functionMutations.isEmpty()) return emptyMap()
        val states = (workflow["states"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: return emptyMap()

        val perState = mutableMapOf<String, MutableMap<String, String>>()
        for (state in states) {
            val name = state["name"] as? String ?: continue
            val actions = (state["actions"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: continue
            for (action in actions) {
                val refName = functionRefName(action) ?: continue
                val mutations = functionMutations[refName] ?: continue
                val target = perState.getOrPut(name) { mutableMapOf() }
                // Last assignment wins — mirrors order of actions in the state,
                // which mirrors execution order at runtime.
                target.putAll(mutations)
            }
        }
        return perState
    }

    /** Extract function name from an action's `functionRef`, string OR object form. */
    private fun functionRefName(action: Map<*, *>): String? = when (val ref = action["functionRef"]) {
        is String -> ref
        is Map<*, *> -> ref["refName"] as? String
        else -> null
    }

    /** For each expression function, the literal `field -> value` it sets. */
    private fun parseFunctions(workflow: Map<*, *>, interested: Set<String>): Map<String, Map<String, String>> {
        val functions = (workflow["functions"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: return emptyMap()
        val out = mutableMapOf<String, Map<String, String>>()
        for (fn in functions) {
            if (fn["type"] != "expression") continue
            val name = fn["name"] as? String ?: continue
            val operation = fn["operation"] as? String ?: continue
            val assignments = parseOperation(operation).filterKeys { it in interested }
            if (assignments.isNotEmpty()) out[name] = assignments
        }
        return out
    }

    /**
     * Best-effort parse of an `expression` function body. Handles the two
     * literal shapes commonly used to mutate workflow data fields:
     *  - object literal:  `{ state: "ACTIVE", phase: "X" }`
     *  - jq assignment:   `.state = "ACTIVE"` (optionally pipe-chained)
     * Dynamic right-hand sides (`.foo`, function calls) are skipped — only
     * `"…"` string literals end up in the result.
     */
    internal fun parseOperation(operation: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Object-literal entries: `<field> : "<value>"`. The field name in jq
        // object literals can be bare or quoted (we accept both).
        OBJECT_ENTRY_RE.findAll(operation).forEach { m ->
            result[m.groupValues[1]] = m.groupValues[2]
        }
        // jq assignment: `.<field> = "<value>"`.
        ASSIGNMENT_RE.findAll(operation).forEach { m ->
            result[m.groupValues[1]] = m.groupValues[2]
        }
        return result
    }

    /** `field: "value"` — bare or quoted key, double-quoted string value. */
    private val OBJECT_ENTRY_RE =
        Regex("""(?:"?)([A-Za-z_][A-Za-z0-9_]*)(?:"?)\s*:\s*"([^"]*)"""")
    /** `.field = "value"`. */
    private val ASSIGNMENT_RE =
        Regex("""\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]*)"""")
}
