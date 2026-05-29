package tech.zacik.workflowviz

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import tech.zacik.workflowviz.inspections.WorkflowDoc
import java.util.ArrayDeque

/**
 * Forward reachability over a workflow's transition graph (BFS from `start`).
 *
 * Shared by:
 *  - [tech.zacik.workflowviz.inspections.UnreachableStatesInspection] to mark
 *    dead states plus every transition literal *inside* them
 *  - [tech.zacik.workflowviz.refs.WorkflowUsageRenderer] to dim popup rows that
 *    point into dead code
 *
 * **Caching.** Result is cached per [PsiFile] via [CachedValuesManager] keyed
 * on the file's PSI modification tracker — recomputed only when the file
 * actually changes, not on every inspection pass or popup row. Without this,
 * a usage popup over a large workflow would BFS N times for N rows; with it,
 * the renderer pays the BFS cost once per edit.
 *
 * Returns the set of state names BFS can reach. Empty when the workflow has
 * no `start` field or `start` itself doesn't resolve — downstream callers
 * treat empty as "no reachability info, don't mark anything as dead".
 */
object WorkflowReachability {

    private val KEY = Key.create<CachedValue<Set<String>>>("workflow.reachability")

    fun reachable(file: PsiFile): Set<String> =
        CachedValuesManager.getManager(file.project).getCachedValue(file, KEY, {
            CachedValueProvider.Result.create(compute(file), file)
        }, false)

    private fun compute(file: PsiFile): Set<String> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptySet()
        val states = WorkflowDoc.objectsOf(root, "states")
            .mapNotNull { state -> WorkflowDoc.nameOf(state)?.let { name -> name to state } }
            .toMap()
        if (states.isEmpty()) return emptySet()
        val start = WorkflowDoc.stringProp(root, "start") ?: return emptySet()
        if (start !in states) return emptySet()

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val cur = queue.poll()
            if (!visited.add(cur)) continue
            val state = states[cur] ?: continue
            for (literal in WorkflowDoc.transitionTargets(state)) {
                val target = literal.value
                if (target in states && target !in visited) queue.add(target)
            }
        }
        return visited
    }
}
