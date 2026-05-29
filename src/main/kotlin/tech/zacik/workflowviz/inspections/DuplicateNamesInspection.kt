package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.json.psi.JsonObject
import com.intellij.psi.PsiFile

/**
 * Reports any second-or-later definition that reuses an existing `name` within
 * `states[]`, `functions[]`, `events[]` or `errors[]` — the analogue of the
 * Python validator's `DuplicateNames` check. Highlights only the duplicates
 * (not the first occurrence), so fixing one removes the report.
 */
class DuplicateNamesInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!WorkflowDoc.isWorkflowFile(file)) return null
        val root = WorkflowDoc.rootOf(file) ?: return null
        val problems = mutableListOf<ProblemDescriptor>()
        for ((collection, kind) in COLLECTIONS) {
            reportDuplicates(WorkflowDoc.objectsOf(root, collection), kind, manager, problems)
        }
        return problems.toTypedArray()
    }

    private fun reportDuplicates(
        items: List<JsonObject>,
        kind: String,
        manager: InspectionManager,
        out: MutableList<ProblemDescriptor>,
    ) {
        val seen = mutableSetOf<String>()
        for (item in items) {
            val literal = WorkflowDoc.nameLiteralOf(item) ?: continue
            val name = literal.value
            if (!seen.add(name)) {
                out.add(
                    manager.createProblemDescriptor(
                        literal,
                        "Duplicate $kind name: '$name'",
                        true,
                        emptyArray(),
                        ProblemHighlightType.GENERIC_ERROR,
                    )
                )
            }
        }
    }

    companion object {
        /** Top-level collections that have a `name` identity column. */
        private val COLLECTIONS = listOf(
            "states" to "state",
            "functions" to "function",
            "events" to "event",
            "errors" to "error",
        )
    }
}
