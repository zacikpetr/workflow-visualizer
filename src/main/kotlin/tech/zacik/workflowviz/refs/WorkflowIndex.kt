package tech.zacik.workflowviz.refs

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import tech.zacik.workflowviz.inspections.WorkflowDoc

/**
 * One-shot cached lookup tables for a workflow file:
 *  - **definitions**: `collection → name → JsonObject` for the four top-level
 *    collections (`states` / `functions` / `events` / `errors`)
 *  - **usages**: `collection → name → list of reference literals` (every
 *    classified string literal pointing at a definition)
 *
 * Both maps are built by a single PSI walk and cached on the [PsiFile] via
 * [CachedValuesManager], so callers that previously re-scanned the whole file
 * per call (`findDefinitionByName`, `findChildrenOfType` in the line-marker
 * provider, autocomplete `getVariants`) become O(1) hash lookups after the
 * first hit. Auto-invalidates on PSI modification.
 *
 * The hot paths this kills:
 *  - `WorkflowReference.resolve` and `getVariants` (Ctrl+B, completion)
 *  - `WorkflowAnnotator` + `WorkflowDefinitionDoc.forReference` (every literal,
 *    every reparse)
 *  - `WorkflowUnresolvedReferenceInspection` (every literal, every pass)
 *  - `WorkflowLineMarkerProvider.findUsages` (per definition × all literals)
 */
object WorkflowIndex {

    data class Index(
        /** `collection → name → first matching definition object`. */
        val definitions: Map<String, Map<String, JsonObject>>,
        /** All declared names per collection (preserves source order for completions). */
        val names: Map<String, List<String>>,
        /** `collection → name → all reference literals` (excludes the definition itself). */
        val usages: Map<String, Map<String, List<JsonStringLiteral>>>,
    )

    private val KEY = Key.create<CachedValue<Index>>("workflow.index")
    private val EMPTY = Index(emptyMap(), emptyMap(), emptyMap())

    fun of(file: PsiFile): Index =
        CachedValuesManager.getManager(file.project).getCachedValue(file, KEY, {
            CachedValueProvider.Result.create(compute(file), file)
        }, false)

    /** First definition object named [name] in [kind]'s collection, or null. */
    fun findDefinition(file: PsiFile, kind: WorkflowReferenceKind, name: String): JsonObject? =
        of(file).definitions[kind.collection]?.get(name)

    /** All reference literals pointing at [name] in [kind]'s collection. */
    fun findUsages(file: PsiFile, kind: WorkflowReferenceKind, name: String): List<JsonStringLiteral> =
        of(file).usages[kind.collection]?.get(name) ?: emptyList()

    /** All declared names in [kind]'s collection, in source order. */
    fun listNames(file: PsiFile, kind: WorkflowReferenceKind): List<String> =
        of(file).names[kind.collection] ?: emptyList()

    private fun compute(file: PsiFile): Index {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return EMPTY

        val definitions = mutableMapOf<String, Map<String, JsonObject>>()
        val names = mutableMapOf<String, List<String>>()
        for (kind in WorkflowReferenceKind.values()) {
            val ordered = mutableListOf<String>()
            val byName = mutableMapOf<String, JsonObject>()
            for (obj in WorkflowDoc.objectsOf(root, kind.collection)) {
                val name = WorkflowDoc.nameOf(obj) ?: continue
                // First occurrence wins — matches the runtime semantics of
                // duplicate names (the duplicate inspection reports the rest).
                if (byName.putIfAbsent(name, obj) == null) ordered += name
            }
            definitions[kind.collection] = byName
            names[kind.collection] = ordered
        }

        // Single PSI walk over every string literal in the file, bucketed by
        // (reference kind, target name). Cheaper than running PsiTreeUtil per
        // definition.
        val usages = mutableMapOf<String, MutableMap<String, MutableList<JsonStringLiteral>>>()
        for (literal in PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java)) {
            val kind = classifyReference(literal) ?: continue
            usages.getOrPut(kind.collection) { mutableMapOf() }
                .getOrPut(literal.value) { mutableListOf() }
                .add(literal)
        }

        return Index(definitions, names, usages)
    }
}
