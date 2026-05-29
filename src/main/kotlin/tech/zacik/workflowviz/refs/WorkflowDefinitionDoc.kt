package tech.zacik.workflowviz.refs

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral

/**
 * Shared HTML renderer for workflow-definition popups. Both the
 * [WorkflowDocumentationProvider] (Ctrl+Q / F1) and the [tech.zacik.workflowviz.highlight.WorkflowAnnotator]
 * tooltip (plain hover) ask the same question — "what does this name point to?"
 * — and want the same rich answer.
 *
 * Two entry points:
 *  - [forReference] takes the **call site** literal (`"transition": "X"`) and
 *    looks up the matching definition.
 *  - [forDefinition] takes the **definition's** `name` literal (the target a
 *    PSI reference resolves to) and renders directly.
 *
 * Both return null when the literal doesn't sit in a recognised workflow shape
 * so callers can fall back to default behaviour.
 */
object WorkflowDefinitionDoc {

    /** HTML for the definition referenced by [literal] (a `transition` / `eventRef` / … value). */
    fun forReference(literal: JsonStringLiteral): String? {
        val kind = classifyReference(literal) ?: return null
        val root = workflowRoot(literal) ?: return null
        val defObj = findDefinitionByName(root, kind.collection, literal.value) ?: return null
        return render(kind.display, literal.value, defObj)
    }

    /** HTML for [target], the `name` value of a definition (`states[].name` etc.). */
    fun forDefinition(target: JsonStringLiteral): String? {
        val nameProp = target.parent as? JsonProperty ?: return null
        if (nameProp.name != "name" || nameProp.value !== target) return null
        val defObj = nameProp.parent as? JsonObject ?: return null
        val arrProp = (defObj.parent as? JsonArray)?.parent as? JsonProperty ?: return null
        val kind = when (arrProp.name) {
            "states" -> "state"
            "functions" -> "function"
            "events" -> "event"
            "errors" -> "error"
            else -> return null
        }
        return render(kind, target.value, defObj)
    }

    private fun render(kind: String, name: String, defObj: JsonObject): String {
        val rows = defObj.propertyList
            .filter { it.name != "name" }
            .joinToString("") { row(it) }
        return buildString {
            append("<html><body>")
            append("<b>")
            append(kind)
            append("</b> <code>")
            append(escapeHtml(name))
            append("</code>")
            if (rows.isNotEmpty()) {
                append("<br><br><table>")
                append(rows)
                append("</table>")
            }
            append("</body></html>")
        }
    }

    private fun row(prop: JsonProperty): String {
        val key = escapeHtml(prop.name)
        val raw = prop.value?.text ?: "?"
        val shown = if (raw.length > MAX_VALUE) raw.substring(0, MAX_VALUE - 1) + "…" else raw
        return "<tr><td><b>$key</b></td><td>&nbsp;${escapeHtml(shown)}</td></tr>"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Cap multi-line array/object text so the popup doesn't grow unbounded. */
    private const val MAX_VALUE = 200
}
