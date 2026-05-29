package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile

/**
 * Workflow with no terminal state — no `end: true` and no state without
 * outgoing transitions/conditionals — is unlikely to complete cleanly. We
 * report a single file-level warning rather than per-state, since "all
 * states have transitions" can't pinpoint a specific offender.
 */
class TerminalStatesInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!WorkflowDoc.isWorkflowFile(file)) return null
        val root = WorkflowDoc.rootOf(file) ?: return null
        val states = WorkflowDoc.objectsOf(root, "states")
        if (states.isEmpty()) return null
        if (states.any { WorkflowDoc.isTerminal(it) }) return null
        return arrayOf(
            manager.createProblemDescriptor(
                WorkflowDoc.rootAnchor(root),
                "Workflow has no terminal state — no 'end: true' and every state has outgoing transitions; may not complete normally",
                true,
                emptyArray(),
                // GENERIC_ERROR_OR_WARNING inherits the severity configured in
                // plugin.xml / Settings → Inspections, so promoting the level
                // to ERROR there actually shows up red in the editor.
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            )
        )
    }
}
