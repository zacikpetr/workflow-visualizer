package tech.zacik.workflowviz.refs

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext

/** What kind of definition a `.sw.json` string literal points to. */
enum class WorkflowReferenceKind(val collection: String, val display: String) {
    STATE("states", "state"),
    FUNCTION("functions", "function"),
    EVENT("events", "event"),
    ERROR("errors", "error"),
}

/**
 * Decide whether [literal] is a reference into one of the top-level workflow
 * collections, based on its surrounding JSON structure. Returns null otherwise.
 *
 * Handles both string and object forms (`"transition": "X"` and
 * `"transition": { "nextState": "X" }`), plus arrays like `eventRefs: [..]`.
 */
fun classifyReference(literal: JsonStringLiteral): WorkflowReferenceKind? {
    val parent = literal.parent
    if (parent is JsonProperty) {
        // Skip property keys — only the *value* is a reference. Without this
        // guard, a property like `"transition": "X"` matches twice: once for
        // its key (which we'd misclassify as a state ref to "transition") and
        // once for its value (the real ref). Hard error on Missing-X inspection.
        if (parent.value !== literal) return null
        return propertyValueKind(parent)
    }
    if (parent is JsonArray) {
        val arrayProperty = parent.parent as? JsonProperty ?: return null
        if (arrayProperty.name == "eventRefs") return WorkflowReferenceKind.EVENT
    }
    return null
}

private fun propertyValueKind(prop: JsonProperty): WorkflowReferenceKind? = when (prop.name) {
    "transition", "compensatedBy" -> WorkflowReferenceKind.STATE
    // `start` is a state reference only at the workflow root — nested `start`
    // keys (schedule blocks, vendor extensions, foreign JSON files) are not
    // state names and must not produce "Missing state definition" errors.
    "start" -> if (isTopLevelProperty(prop)) WorkflowReferenceKind.STATE else null
    "nextState" -> if (insideProperty(prop, "transition")) WorkflowReferenceKind.STATE else null
    // Object form of start: `"start": { "stateName": "X", … }`.
    "stateName" -> if (insideProperty(prop, "start")) WorkflowReferenceKind.STATE else null
    "functionRef" -> WorkflowReferenceKind.FUNCTION
    "refName" -> if (insideProperty(prop, "functionRef")) WorkflowReferenceKind.FUNCTION else null
    "eventRef", "triggerEventRef" -> WorkflowReferenceKind.EVENT
    "errorRef" -> WorkflowReferenceKind.ERROR
    else -> null
}

/** True if [prop] sits directly in the file's top-level object. */
private fun isTopLevelProperty(prop: JsonProperty): Boolean =
    (prop.parent as? JsonObject)?.parent is JsonFile

/** True if [prop]'s value object sits inside an outer property of [expectedName]. */
private fun insideProperty(prop: JsonProperty, expectedName: String): Boolean {
    val outerObj = prop.parent as? JsonObject ?: return false
    val outerProp = outerObj.parent as? JsonProperty ?: return false
    return outerProp.name == expectedName
}

/** Top-level workflow JSON object, or null if the file isn't a JSON object. */
fun workflowRoot(element: PsiElement): JsonObject? =
    (element.containingFile as? JsonFile)?.topLevelValue as? JsonObject

/**
 * First object with `name == [name]` in workflow's top-level [collection], or
 * null. Backed by [WorkflowIndex] — cached per PSI version, so repeated
 * lookups within one analysis pass cost O(1) after the first call.
 */
fun findDefinitionByName(workflow: JsonObject, collection: String, name: String): JsonObject? {
    val file = workflow.containingFile ?: return null
    val kind = WorkflowReferenceKind.values().firstOrNull { it.collection == collection } ?: return null
    return WorkflowIndex.findDefinition(file, kind, name)
}

/** All declared names in the workflow's top-level [collection]. Source-ordered. */
fun listDefinitionNames(workflow: JsonObject, collection: String): List<String> {
    val file = workflow.containingFile ?: return emptyList()
    val kind = WorkflowReferenceKind.values().firstOrNull { it.collection == collection } ?: return emptyList()
    return WorkflowIndex.listNames(file, kind)
}

// ── PsiReference plumbing ───────────────────────────────────────────────────

class WorkflowReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JsonStringLiteral::class.java),
            WorkflowReferenceProvider,
        )
    }
}

private object WorkflowReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
        val kind = classifyReference(literal) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(WorkflowReference(literal, kind))
    }
}

/**
 * A reference from a `.sw.json` value to the matching definition in the
 * workflow's `states[] / functions[] / events[] / errors[]`. Drives Go to
 * Declaration (Ctrl+B), Find Usages and Rename.
 */
class WorkflowReference(
    literal: JsonStringLiteral,
    private val kind: WorkflowReferenceKind,
) : PsiReferenceBase<JsonStringLiteral>(literal, valueRange(literal), false) {

    override fun resolve(): PsiElement? {
        val workflow = workflowRoot(element) ?: return null
        val target = element.value
        val def = findDefinitionByName(workflow, kind.collection, target) ?: return null
        // Navigate to the `"name"` value of the definition — that's where the
        // caret lands. Resolved through a POM-target wrapper: a bare
        // JsonStringLiteral is not a PsiNamedElement, so the platform would
        // refuse Rename on it (see WorkflowDefinitionTarget).
        val nameLiteral = def.findProperty("name")?.value as? JsonStringLiteral ?: return null
        return WorkflowDefinitionTarget.psiFor(nameLiteral)
    }

    override fun getVariants(): Array<Any> {
        val workflow = workflowRoot(element) ?: return emptyArray()
        return listDefinitionNames(workflow, kind.collection)
            .map { LookupElementBuilder.create(it).withTypeText(kind.display) }
            .toTypedArray()
    }

    companion object {
        /** Range covering only the inside of the quotes (excludes the surrounding `"`). */
        private fun valueRange(literal: JsonStringLiteral): TextRange {
            val len = literal.textLength
            return if (len >= 2) TextRange(1, len - 1) else TextRange(0, len)
        }
    }
}
