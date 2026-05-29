package tech.zacik.workflowviz.highlight

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import tech.zacik.workflowviz.refs.WorkflowDefinitionDoc
import tech.zacik.workflowviz.refs.classifyReference
import tech.zacik.workflowviz.settings.WorkflowVizSettings

/**
 * Paints semantic highlights on `.sw.json`:
 *
 *  - **Reference values** (the `"X"` under `transition` / `functionRef` / …)
 *    get a [WorkflowColors] reference key.
 *  - **State name definitions** (the `"X"` under `states[i].name`) get a key
 *    chosen by the state's `type` plus start/end specials.
 *
 * Toggleable via [WorkflowVizSettings.State.semanticColoring]. We check that
 * setting on every literal — cheap enough — so the toggle takes effect on
 * the next reparse without an IDE restart.
 *
 * Range is `TextRange.from(literal.start, literal.length)` (whole literal,
 * including the surrounding quotes). Highlighting just the inner value would
 * fight with the JSON lexer's own string colour and looks chopped up.
 */
class WorkflowAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is JsonStringLiteral) return
        if (!WorkflowVizSettings.getInstance().state.semanticColoring) return
        if (!isWorkflowFile(element)) return

        val key = colorKeyFor(element) ?: return
        val builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(key)
        // For reference values, attach the definition body as the hover tooltip
        // so users see what the link points to without F1 / Ctrl+Q.
        WorkflowDefinitionDoc.forReference(element)?.let { builder.tooltip(it) }
        builder.create()
    }

    /** Pick a colour key for [literal], or null if it isn't a workflow-semantic literal. */
    private fun colorKeyFor(literal: JsonStringLiteral): TextAttributesKey? {
        // Reference value (transition / functionRef / eventRef / errorRef …).
        classifyReference(literal)?.let { kind ->
            return when (kind.collection) {
                "states" -> WorkflowColors.STATE_REFERENCE
                "functions" -> WorkflowColors.FUNCTION_REFERENCE
                "events" -> WorkflowColors.EVENT_REFERENCE
                "errors" -> WorkflowColors.ERROR_REFERENCE
                else -> null
            }
        }
        // Definition `name` value (states / functions / events / errors).
        return definitionNameKey(literal)
    }

    /** Returns the right [WorkflowColors] key when [literal] is a definition's `name`. */
    private fun definitionNameKey(literal: JsonStringLiteral): TextAttributesKey? {
        val nameProp = literal.parent as? JsonProperty ?: return null
        if (nameProp.name != "name" || nameProp.value !== literal) return null
        val defObj = nameProp.parent as? JsonObject ?: return null
        val array = defObj.parent as? JsonArray ?: return null
        val arrayProp = array.parent as? JsonProperty ?: return null

        return when (arrayProp.name) {
            "states" -> stateNameKey(literal, defObj)
            "functions" -> WorkflowColors.FUNCTION_NAME
            "events" -> WorkflowColors.EVENT_NAME
            "errors" -> WorkflowColors.ERROR_NAME
            else -> null
        }
    }

    /** Pick the per-type / start / end colour key for a `states[].name` value. */
    private fun stateNameKey(literal: JsonStringLiteral, stateObj: JsonObject): TextAttributesKey {
        // Start state takes priority over the type colour — it's the entrypoint.
        val root = workflowRootOf(literal)
        val startName = (root?.findProperty("start")?.value as? JsonStringLiteral)?.value
        if (startName != null && startName == literal.value) return WorkflowColors.STATE_NAME_START
        if (stateObj.findProperty("end")?.value?.text == "true") return WorkflowColors.STATE_NAME_END

        val type = (stateObj.findProperty("type")?.value as? JsonStringLiteral)?.value
        return when (type) {
            "operation" -> WorkflowColors.STATE_NAME_OPERATION
            "switch" -> WorkflowColors.STATE_NAME_SWITCH
            "event" -> WorkflowColors.STATE_NAME_EVENT
            "foreach" -> WorkflowColors.STATE_NAME_FOREACH
            else -> WorkflowColors.STATE_NAME
        }
    }

    private fun workflowRootOf(element: PsiElement): JsonObject? =
        (element.containingFile as? JsonFile)?.topLevelValue as? JsonObject

    private fun isWorkflowFile(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        if (file.name.endsWith(".sw.json")) return true
        val root = workflowRootOf(element) ?: return false
        return root.findProperty("states")?.value is JsonArray
    }
}
