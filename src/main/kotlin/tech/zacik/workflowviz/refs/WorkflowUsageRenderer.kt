package tech.zacik.workflowviz.refs

import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import tech.zacik.workflowviz.WorkflowReachability

/**
 * Renders gutter-popup usage entries as **JSON paths within their enclosing
 * state** instead of repeating the literal value (which is identical for every
 * row — the popup title already names what we're looking at).
 *
 * Example: a usage at `states[5].dataConditions[0].transition` shows as
 * `state 'returnToContinuationPoint' → dataConditions[0].transition`.
 * The right-hand container text keeps the file name (default behaviour).
 */
class WorkflowUsageRenderer : PsiElementListCellRenderer<JsonStringLiteral>() {

    override fun getElementText(element: JsonStringLiteral): String {
        val (stateName, path) = describeReferenceLocation(element)
        val context = if (stateName != null) "state '$stateName'" else "workflow root"
        val main = if (path.isEmpty()) context else "$context → $path"
        val hint = branchHintFor(element)
        val raw = if (hint != null) "$main  «$hint»" else main
        return if (isInDeadCode(element, stateName)) deadCode(raw) else raw
    }

    /**
     * The enclosing state's reachability tells us whether *this* usage will
     * ever fire — references inside dead states are dead themselves, even
     * though they "resolve" cleanly. Workflow-root usages (e.g. `start`)
     * always count as live.
     */
    private fun isInDeadCode(literal: JsonStringLiteral, stateName: String?): Boolean {
        if (stateName == null) return false
        val file = literal.containingFile ?: return false
        val reachable = WorkflowReachability.reachable(file)
        if (reachable.isEmpty()) return false // no reachability info → don't dim
        return stateName !in reachable
    }

    /**
     * Wrap in HTML so JBList's JLabel paints it struck-through + grey. We
     * deliberately don't HTML-escape the input — the row text is built from
     * controlled fragments (`state '…'`, path segments, branch hints) and may
     * contain `<` / `>` characters of jq comparisons that should render literally.
     * In practice no JSON workflow name contains `<` and the quill brackets
     * `«»` survive Swing's HTML parser fine.
     */
    private fun deadCode(text: String): String =
        "<html><s><font color='#888888'>${text.replace("<", "&lt;")}</font></s></html>"

    /**
     * Secondary text is intentionally empty. We previously prefixed each row
     * with `<line> ·` for fast scanning, but variable-width line numbers
     * (1–4 digits) misalign the path column in the proportional popup font.
     * Click on the row already navigates to the right offset, which covers
     * the "where is it" use case. Reintroduce with a custom two-column
     * renderer (or a monospace `<tt>` HTML prefix) if alignment is solved.
     * Same future spot for the file name when the plugin goes cross-project.
     */
    override fun getContainerText(element: JsonStringLiteral, name: String): String? = null

    override fun getIconFlags(): Int = 0
}

/**
 * For a transition target literal sitting inside `dataConditions[i]`,
 * `eventConditions[i]` or `onErrors[i]`, returns the *discriminator* of that
 * enclosing branch — the value of `condition`, `eventRef`, or `errorRef`
 * respectively. Lets the popup row read "→ dataConditions[0].transition  «$.x
 * > 10»" so you don't have to expand to see which branch points where.
 *
 * Only fires for transition / nextState literals; `eventConditions[i].eventRef`
 * literal already IS its own discriminator, no point echoing it.
 */
private fun branchHintFor(literal: JsonStringLiteral): String? {
    val parent = literal.parent as? JsonProperty ?: return null
    if (parent.name != "transition" && parent.name != "nextState") return null
    var cur: PsiElement = parent
    while (true) {
        val p = cur.parent ?: return null
        if (p is JsonObject) {
            val arrProp = (p.parent as? JsonArray)?.parent as? JsonProperty
            val hint = when (arrProp?.name) {
                "dataConditions" -> stringValueOf(p, "condition")
                "eventConditions" -> stringValueOf(p, "eventRef")
                "onErrors" -> stringValueOf(p, "errorRef")
                else -> null
            }
            if (hint != null) return truncate(hint, 40)
        }
        cur = p
    }
}

private fun stringValueOf(obj: JsonObject, name: String): String? =
    (obj.findProperty(name)?.value as? JsonStringLiteral)?.value

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max - 1) + "…"

/**
 * Walks up the PSI from [literal], collecting JSON-path segments until either
 *  - it reaches the enclosing top-level state object (member of `states[]`),
 *    in which case it returns `(stateName, "<path-within-state>")`, or
 *  - it reaches the workflow root, returning `(null, "<path-at-root>")`
 *    (e.g. `(null, "start")` for `workflow.start`).
 *
 * Indices on array elements attach to the wrapping property name —
 * `actions[2].functionRef` — to read like jq selectors.
 */
private fun describeReferenceLocation(literal: JsonStringLiteral): Pair<String?, String> {
    val segments = mutableListOf<String>()
    var cur: PsiElement = literal
    /** Index in the current array we last stepped through; folded into the next property name we see. */
    var pendingIndex: Int? = null
    while (true) {
        val parent = cur.parent ?: break
        when (parent) {
            is JsonProperty -> {
                val seg = if (pendingIndex != null) "${parent.name}[$pendingIndex]" else parent.name
                segments += seg
                pendingIndex = null
                cur = parent
            }
            is JsonArray -> {
                pendingIndex = parent.valueList.indexOf(cur)
                cur = parent
            }
            is JsonObject -> {
                // Are we sitting inside a top-level `states[]` entry?
                val outerArr = parent.parent as? JsonArray
                val outerProp = outerArr?.parent as? JsonProperty
                if (outerProp?.name == "states") {
                    val name = (parent.findProperty("name")?.value as? JsonStringLiteral)?.value
                    return name to segments.reversed().joinToString(".")
                }
                cur = parent
            }
            else -> break
        }
    }
    return null to segments.reversed().joinToString(".")
}
