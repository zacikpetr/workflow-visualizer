package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile

/**
 * States defined in `states[]` but never referenced — neither as `start` nor as
 * the target of any transition. Stricter than [UnreachableStatesInspection]:
 * "unreachable" still fires when a dead branch references the state, "unused"
 * only fires when *no* edge points at it.
 *
 * The two inspections overlap heavily in practice; both fire only on truly
 * orphan states. Disable one from Settings → Inspections if the duplication
 * gets noisy on a project.
 */
class UnusedStatesInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!WorkflowDoc.isWorkflowFile(file)) return null
        val root = WorkflowDoc.rootOf(file) ?: return null
        val states = WorkflowDoc.objectsOf(root, "states")
        if (states.isEmpty()) return null

        val referenced = mutableSetOf<String>()
        WorkflowDoc.stringProp(root, "start")?.let { referenced.add(it) }
        for (state in states) {
            for (literal in WorkflowDoc.transitionTargets(state)) referenced.add(literal.value)
        }

        val problems = mutableListOf<ProblemDescriptor>()
        for (state in states) {
            val name = WorkflowDoc.nameOf(state) ?: continue
            if (name in referenced) continue
            val anchor = WorkflowDoc.nameLiteralOf(state) ?: continue
            problems.add(
                manager.createProblemDescriptor(
                    anchor,
                    "Unused state '$name' — not referenced by start or any transition",
                    true,
                    emptyArray(),
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                )
            )
        }
        return problems.toTypedArray()
    }
}
