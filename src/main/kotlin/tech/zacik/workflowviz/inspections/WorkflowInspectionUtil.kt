package tech.zacik.workflowviz.inspections

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonBooleanLiteral
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Shared helpers for `.sw.json` inspections. Centralised so each inspection
 * stays small and the "is this a workflow file?" decision lives in one place.
 */
internal object WorkflowDoc {

    /**
     * True if [file] looks like a Serverless Workflow `.sw.json`. We accept the
     * filename convention OR a top-level JSON object that has a `states` array —
     * the same heuristic the diagram uses, so inspections fire exactly when the
     * preview does.
     */
    fun isWorkflowFile(file: PsiFile): Boolean {
        if (file.name.endsWith(".sw.json")) return true
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return false
        return root.findProperty("states")?.value is JsonArray
    }

    /** Top-level workflow object of [file], or null if it isn't a JSON object. */
    fun rootOf(file: PsiFile): JsonObject? =
        (file as? JsonFile)?.topLevelValue as? JsonObject

    /** The array value of a top-level property like `states` / `functions`, or empty. */
    fun arrayOf(root: JsonObject, propertyName: String): JsonArray? =
        root.findProperty(propertyName)?.value as? JsonArray

    /** All object entries of a top-level array property (e.g. each state). */
    fun objectsOf(root: JsonObject, propertyName: String): List<JsonObject> =
        arrayOf(root, propertyName)?.valueList?.filterIsInstance<JsonObject>() ?: emptyList()

    /** The `"name"` string literal of [obj], or null if missing / not a string. */
    fun nameLiteralOf(obj: JsonObject): JsonStringLiteral? =
        obj.findProperty("name")?.value as? JsonStringLiteral

    /** The string value of [obj]'s `"name"`, or null. */
    fun nameOf(obj: JsonObject): String? = nameLiteralOf(obj)?.value

    /** The string value of [obj]'s `"type"`, or null. */
    fun typeOf(obj: JsonObject): String? = (obj.findProperty("type")?.value as? JsonStringLiteral)?.value

    /** Read a string-valued property — convenience. */
    fun stringProp(obj: JsonObject, name: String): String? =
        (obj.findProperty(name)?.value as? JsonStringLiteral)?.value

    /** Read a boolean-valued property (e.g. `end`) — convenience. */
    fun boolProp(obj: JsonObject, name: String): Boolean? =
        (obj.findProperty(name)?.value as? JsonBooleanLiteral)?.value

    /**
     * Element to anchor a file-level diagnostic on (so highlighting is visible).
     * Prefer the `name` property identifier, else the first property of [root],
     * else the root itself.
     */
    fun rootAnchor(root: JsonObject): PsiElement =
        (root.findProperty("name") as PsiElement?)
            ?: (root.propertyList.firstOrNull() as PsiElement?)
            ?: root

    /**
     * Collect every transition target (state name) reachable from [state] —
     * `transition`, `eventConditions[].transition`, `dataConditions[].transition`,
     * `defaultCondition.transition`, `onErrors[].transition` — mirroring the
     * Python validator's graph builder.
     *
     * Returned literals' `.value` is the state name; the literal itself is the
     * PSI anchor (useful for "unreachable" highlighting, etc.).
     */
    fun transitionTargets(state: JsonObject): List<JsonStringLiteral> {
        val out = mutableListOf<JsonStringLiteral>()

        fun addTransition(transitionProp: JsonProperty?) {
            val v = transitionProp?.value ?: return
            when (v) {
                is JsonStringLiteral -> out += v
                is JsonObject -> (v.findProperty("nextState")?.value as? JsonStringLiteral)?.let { out += it }
                else -> { /* arrays / other shapes not used for state-name transitions */ }
            }
        }

        addTransition(state.findProperty("transition"))

        for (ec in (state.findProperty("eventConditions")?.value as? JsonArray)?.valueList?.filterIsInstance<JsonObject>().orEmpty()) {
            addTransition(ec.findProperty("transition"))
        }
        for (dc in (state.findProperty("dataConditions")?.value as? JsonArray)?.valueList?.filterIsInstance<JsonObject>().orEmpty()) {
            addTransition(dc.findProperty("transition"))
        }

        val default = state.findProperty("defaultCondition")?.value as? JsonObject
        default?.findProperty("transition")?.let { addTransition(it) }

        for (err in (state.findProperty("onErrors")?.value as? JsonArray)?.valueList?.filterIsInstance<JsonObject>().orEmpty()) {
            addTransition(err.findProperty("transition"))
        }
        return out
    }

    /** True if [state] is terminal — `end: true` or no outbound transitions/conditionals. */
    fun isTerminal(state: JsonObject): Boolean {
        if (boolProp(state, "end") == true) return true
        val hasTransition = state.findProperty("transition") != null
        val hasConditionals = state.findProperty("eventConditions") != null ||
            state.findProperty("dataConditions") != null ||
            state.findProperty("defaultCondition") != null
        return !hasTransition && !hasConditionals
    }
}
