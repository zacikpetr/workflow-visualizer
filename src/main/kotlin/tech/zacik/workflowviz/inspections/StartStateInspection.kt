package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiFile

/**
 * Workflow must declare `start` and that state must exist.
 *
 * The "undefined target" half is already covered by [tech.zacik.workflowviz.refs.WorkflowReference]
 * (Unresolved reference on `start`'s value when no `states[].name` matches), so
 * here we only report the **missing-altogether** case.
 */
class StartStateInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!WorkflowDoc.isWorkflowFile(file)) return null
        val root = WorkflowDoc.rootOf(file) ?: return null
        val startProp = root.findProperty("start")
        if (startProp == null) {
            return arrayOf(
                manager.createProblemDescriptor(
                    WorkflowDoc.rootAnchor(root),
                    "No 'start' state defined",
                    true,
                    emptyArray(),
                    ProblemHighlightType.GENERIC_ERROR,
                )
            )
        }
        // A `start` whose value isn't even a string (e.g. number/object) — quietly
        // bail; the JSON-schema layer / PSI resolution will already complain.
        if (startProp.value !is JsonStringLiteral) return null
        return null
    }
}
