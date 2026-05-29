package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import tech.zacik.workflowviz.WorkflowReachability

/**
 * BFS from `start` over transition edges (see [WorkflowReachability]). States
 * that aren't visited get a `LIKE_UNUSED_SYMBOL` highlight on their `name`,
 * **plus** every transition literal inside such a dead state — those edges can
 * never fire because the source never executes, and marking them visually
 * reinforces "this whole block is dead".
 *
 * Silent (returns no problems) when `start` is missing or undefined: that's
 * already reported by [StartStateInspection] / the unresolved-reference pass,
 * and without a root we can't tell reachable from unused.
 */
class UnreachableStatesInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!WorkflowDoc.isWorkflowFile(file)) return null
        val root = WorkflowDoc.rootOf(file) ?: return null
        val states = WorkflowDoc.objectsOf(root, "states")
            .mapNotNull { state -> WorkflowDoc.nameOf(state)?.let { name -> name to state } }
            .toMap()
        if (states.isEmpty()) return null
        val start = WorkflowDoc.stringProp(root, "start") ?: return null
        if (start !in states) return null

        val reachable = WorkflowReachability.reachable(file)
        val problems = mutableListOf<ProblemDescriptor>()
        for ((name, state) in states) {
            if (name in reachable) continue
            // 1) Dead state's name itself.
            WorkflowDoc.nameLiteralOf(state)?.let { anchor ->
                problems.add(
                    manager.createProblemDescriptor(
                        anchor,
                        "Unreachable state '$name' — no transition path from start '$start'",
                        true,
                        emptyArray(),
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                )
            }
            // 2) Each transition *out of* the dead state — also unreachable
            //    because the source never runs. Per-edge markers make it
            //    obvious which lines belong to the dead block when scrolling.
            for (transitionLiteral in WorkflowDoc.transitionTargets(state)) {
                problems.add(
                    manager.createProblemDescriptor(
                        transitionLiteral,
                        "Unreachable transition — source state '$name' is unreachable",
                        true,
                        emptyArray(),
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                )
            }
        }
        return problems.toTypedArray()
    }
}
