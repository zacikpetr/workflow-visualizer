package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiFile

/**
 * Switch states with `dataConditions` should declare a `defaultCondition` — if
 * none of the conditions match the workflow has nowhere to go and deadlocks.
 *
 * Event-only switches (i.e. `eventConditions` with `timeouts`) are excluded:
 * the timeout itself acts as the fallback.
 */
class SwitchDefaultInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!WorkflowDoc.isWorkflowFile(file)) return null
        val root = WorkflowDoc.rootOf(file) ?: return null
        val problems = mutableListOf<ProblemDescriptor>()
        for (state in WorkflowDoc.objectsOf(root, "states")) {
            checkState(state, manager)?.let { problems.add(it) }
        }
        return problems.toTypedArray()
    }

    private fun checkState(
        state: JsonObject,
        manager: InspectionManager,
    ): ProblemDescriptor? {
        if (WorkflowDoc.typeOf(state) != "switch") return null
        val hasDefault = state.findProperty("defaultCondition") != null
        val hasTimeouts = state.findProperty("timeouts") != null
        val hasEventConds = state.findProperty("eventConditions") != null
        val hasDataConds = state.findProperty("dataConditions") != null

        // Event switches with a timeout are fine — timeout is the fallback.
        if (hasEventConds && hasTimeouts) return null
        if (!hasDataConds || hasDefault) return null

        val name = WorkflowDoc.nameOf(state) ?: "?"
        val anchor = WorkflowDoc.nameLiteralOf(state) ?: return null
        return manager.createProblemDescriptor(
            anchor,
            "Switch state '$name' has no defaultCondition — potential deadlock if no condition matches",
            true,
            emptyArray(),
            ProblemHighlightType.WARNING,
        )
    }
}
