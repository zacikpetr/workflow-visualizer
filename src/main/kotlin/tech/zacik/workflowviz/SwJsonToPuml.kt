package tech.zacik.workflowviz

/**
 * Serverless Workflow (.sw.json) -> PlantUML state diagram.
 *
 * Kotlin port of `plantuml/swjson_to_puml.py` — mirrors the edge extraction of the
 * Mermaid generator (transition / dataConditions / eventConditions / defaultCondition
 * / onErrors + start/end + per-type colours) so both renderers stay in sync.
 *
 * Emits `!pragma layout smetana` (render without graphviz) and a clickable
 * `[[swjson://<name>]]` anchor on each state (the anchor must come BEFORE the
 * colour, else PlantUML rejects the line).
 */
object SwJsonToPuml {

    private val TYPE_COLOR = mapOf(
        "event" to "#b8daff;line:6aa3e0",
        "switch" to "#fff3cd;line:d4a944",
        "operation" to "#d4edda;line:7ab98a",
        "foreach" to "#e2d4f5;line:9a7ec0",
    )
    private const val START_COLOR = "#a8d5ba;line:5a9a6e"
    private const val END_COLOR = "#f5c6cb;line:d98a8a"

    /**
     * Per-field tint for mutation badges — a GitHub-style palette tuned to be
     * legible on the diagram's pastel state backgrounds. Custom fields not in
     * the map fall back to grey.
     */
    private val BADGE_COLOR = mapOf(
        "state" to "#1a7f37",
        "phase" to "#8250df",
        "continuationPoint" to "#bc4c00",
        "stateDetail" to "#0969da",
    )
    private const val BADGE_DEFAULT_COLOR = "#777"

    // Emphasis colour for the state the editor caret is currently in (locate).
    private const val LOCATE_COLOR = "#ffd54a;line:c98a00"

    /** Dim grey fill + line for unreachable states (and their outgoing edges). */
    private const val UNREACHABLE_COLOR = "#ececec;line:bcbcbc"
    /** Arrow colour (hex without `#`) used for edges originating in an unreachable state. */
    private const val UNREACHABLE_EDGE = "bcbcbc"

    fun sanitize(name: String?): String = (name ?: "").replace(Regex("[^a-zA-Z0-9]"), "_")

    /** Diagram-rendering knobs sourced from [tech.zacik.workflowviz.settings.WorkflowVizSettings]. */
    data class Config(
        /** Max chars on edge labels; `0` disables truncation. */
        val labelMaxChars: Int = 25,
        /** Tint `onErrors` edges red so failure paths are visible at a glance. */
        val colorErrorEdges: Boolean = true,
        /** Annotate each state with the runtime fields its actions mutate (`state: "ACTIVE"` …). */
        val showMutationBadges: Boolean = false,
        /** Which mutated fields to surface — empty = none. */
        val mutationBadgeFields: Set<String> = emptySet(),
        /** Render states unreachable from `start` (and their outgoing edges) in dim grey. */
        val dimUnreachable: Boolean = true,
    )

    private fun label(text: String, limit: Int): String {
        var t = text.replace(Regex("\\s+"), " ").trim()
        if (limit > 0 && t.length > limit) t = t.substring(0, (limit - 2).coerceAtLeast(1)) + ".."
        return t
    }

    private fun targetOf(transition: Any?): String? = when (transition) {
        is Map<*, *> -> transition["nextState"] as? String
        is String -> transition
        else -> null
    }

    private fun colorFor(state: Map<*, *>, start: String?, locate: String?, isUnreachable: Boolean): String {
        val name = state["name"] as? String
        // Locate wins over everything — user explicitly pointed there.
        if (name != null && name == locate) return LOCATE_COLOR
        // Unreachable next — even if the state is `start`-typed or a terminal,
        // the fact that BFS can't reach it is the dominant signal.
        if (isUnreachable) return UNREACHABLE_COLOR
        if (name == start) return START_COLOR
        if (state["end"] == true) return END_COLOR
        return TYPE_COLOR[state["type"] as? String] ?: ""
    }

    /**
     * BFS over the same transition channels we use elsewhere. Returns the
     * names BFS reaches from `start`; if `start` is missing or doesn't match
     * any state, returns null = "no info, don't dim anything".
     */
    private fun reachableSet(workflow: Map<*, *>): Set<String>? {
        val statesByName = (workflow["states"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.mapNotNull { s -> (s["name"] as? String)?.let { it to s } }
            ?.toMap()
            ?: return null
        val start = workflow["start"] as? String ?: return null
        if (start !in statesByName) return null

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!visited.add(cur)) continue
            val state = statesByName[cur] ?: continue
            for (target in transitionTargets(state)) {
                if (target in statesByName && target !in visited) queue.add(target)
            }
        }
        return visited
    }

    /** All state-name transition targets out of [state]. Mirrors the PSI-side helper. */
    private fun transitionTargets(state: Map<*, *>): List<String> {
        val out = mutableListOf<String>()
        targetOf(state["transition"])?.let { out += it }
        (state["dataConditions"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { c ->
            targetOf(c["transition"])?.let { out += it }
        }
        (state["eventConditions"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { c ->
            targetOf(c["transition"])?.let { out += it }
        }
        (state["defaultCondition"] as? Map<*, *>)?.let { dc ->
            targetOf(dc["transition"])?.let { out += it }
        }
        (state["onErrors"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { err ->
            targetOf(err["transition"])?.let { out += it }
        }
        return out
    }

    /**
     * @param workflow parsed .sw.json (see [WorkflowJson.parse])
     * @param locate   optional state name to emphasise (caret → locate)
     * @param config   diagram-rendering knobs (label length, error-edge colour)
     */
    fun toPuml(workflow: Map<*, *>, locate: String? = null, config: Config = Config()): String {
        val states = (workflow["states"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val start = workflow["start"] as? String
        // Pre-compute mutation badges so the per-state loop below stays simple.
        val badges = if (config.showMutationBadges && config.mutationBadgeFields.isNotEmpty()) {
            MutationBadges.compute(workflow, config.mutationBadgeFields)
        } else emptyMap()
        // Reachable set (or null when start is missing / unresolvable — then we
        // can't tell live from dead, so nothing gets dimmed).
        val reachable = if (config.dimUnreachable) reachableSet(workflow) else null
        val out = StringBuilder()

        out.appendLine("@startuml")
        out.appendLine("!pragma layout smetana")
        out.appendLine("hide empty description")
        out.appendLine("skinparam shadowing false")
        out.appendLine("skinparam state {")
        out.appendLine("  BackgroundColor #f6f8fa")
        out.appendLine("  BorderColor #8a8aa0")
        out.appendLine("  FontName sans-serif")
        out.appendLine("}")
        out.appendLine()

        // State declarations: alias + clickable anchor (before colour!) + colour.
        for (state in states) {
            val name = state["name"] as? String ?: "?"
            val alias = sanitize(name)
            val isUnreachable = reachable != null && name !in reachable
            // Strike the label through for unreachable nodes — mirrors the
            // `LIKE_UNUSED_SYMBOL` editor highlight for the same state name.
            val labelText = if (isUnreachable) "<s>$name</s>" else name
            var decl = "state \"$labelText\" as $alias [[swjson://$name]]"
            val color = colorFor(state, start, locate, isUnreachable)
            if (color.isNotEmpty()) decl += " $color"
            out.appendLine(decl)
            // Mutation badges render as **state descriptions** (`alias : …`) —
            // PlantUML draws a divider between the name and the description
            // block, which is exactly the "name | fields" look we want. Creole
            // markup tints the field name grey and bolds the value.
            badges[name]?.forEach { (field, value) ->
                val safeValue = value.replace("\"", "\\\"")
                val tint = BADGE_COLOR[field] ?: BADGE_DEFAULT_COLOR
                out.appendLine("$alias : <color:$tint>$field:</color> <b>$safeValue</b>")
            }
        }
        out.appendLine()

        if (start != null) out.appendLine("[*] --> ${sanitize(start)}")

        // Transitions — same channels as the Mermaid generator.
        for (state in states) {
            val name = state["name"] as? String ?: "?"
            val alias = sanitize(name)
            val sourceDead = reachable != null && name !in reachable
            // Dead-source edges are themselves dead — paint them grey so they
            // recede with the node. Live error edges still go red; dead error
            // edges are grey (the unreachable signal trumps the error one).
            val normalArrow = if (sourceDead) "-[#$UNREACHABLE_EDGE]->" else "-->"
            val errorArrow = when {
                sourceDead -> "-[#$UNREACHABLE_EDGE]->"
                config.colorErrorEdges -> "-[#d33]->"
                else -> "-->"
            }
            val errorLabel = when {
                sourceDead -> "<color:#$UNREACHABLE_EDGE>error</color>"
                config.colorErrorEdges -> "<color:#d33>error</color>"
                else -> "error"
            }

            targetOf(state["transition"])?.let { out.appendLine("$alias $normalArrow ${sanitize(it)}") }

            (state["dataConditions"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEachIndexed { i, c ->
                targetOf(c["transition"])?.let {
                    val lbl = (c["name"] as? String) ?: "cond${i + 1}"
                    out.appendLine("$alias $normalArrow ${sanitize(it)} : ${label(lbl, config.labelMaxChars)}")
                }
            }
            (state["eventConditions"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEachIndexed { i, c ->
                targetOf(c["transition"])?.let {
                    val lbl = (c["name"] as? String) ?: (c["eventRef"] as? String) ?: "evt${i + 1}"
                    out.appendLine("$alias $normalArrow ${sanitize(it)} : ${label(lbl, config.labelMaxChars)}")
                }
            }
            (state["defaultCondition"] as? Map<*, *>)?.let { dc ->
                targetOf(dc["transition"])?.let { out.appendLine("$alias $normalArrow ${sanitize(it)} : default") }
            }
            (state["onErrors"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { err ->
                targetOf(err["transition"])?.let { out.appendLine("$alias $errorArrow ${sanitize(it)} : $errorLabel") }
            }
            if (state["end"] == true) out.appendLine("$alias $normalArrow [*]")
        }

        out.appendLine("@enduml")
        return out.toString()
    }
}
