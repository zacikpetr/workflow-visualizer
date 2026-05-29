package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiFile

/**
 * Event-waiting states without a `timeouts` fallback may wait indefinitely.
 * Covers two shapes:
 *   - `type: event` with no `timeouts`
 *   - `type: switch` with `eventConditions` but neither `timeouts` nor `defaultCondition`
 */
class EventTimeoutInspection : LocalInspectionTool() {

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
        val type = WorkflowDoc.typeOf(state) ?: return null
        val hasTimeout = state.findProperty("timeouts") != null

        val message = when {
            type == "event" && !hasTimeout ->
                "Event state '${WorkflowDoc.nameOf(state) ?: "?"}' has no timeout — may wait indefinitely"

            type == "switch" && state.findProperty("eventConditions") != null
                && !hasTimeout && state.findProperty("defaultCondition") == null ->
                "Switch state '${WorkflowDoc.nameOf(state) ?: "?"}' waits for events without timeout or defaultCondition — may wait indefinitely"

            else -> return null
        }

        val anchor = WorkflowDoc.nameLiteralOf(state) ?: return null
        return manager.createProblemDescriptor(
            anchor,
            message,
            true,
            emptyArray(),
            ProblemHighlightType.WARNING,
        )
    }
}
