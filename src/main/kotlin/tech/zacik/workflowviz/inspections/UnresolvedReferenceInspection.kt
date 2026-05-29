package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElementVisitor
import tech.zacik.workflowviz.refs.classifyReference
import tech.zacik.workflowviz.refs.findDefinitionByName
import tech.zacik.workflowviz.refs.workflowRoot

/**
 * Flag string literals that look like state / function / event / error references
 * (per [classifyReference]) but resolve to no definition. Combines the
 * Missing{States,Functions,Events,Errors} checks from the Python validator into
 * one inspection — they share the same shape: classify a literal by its JSON
 * path, then look it up in the corresponding top-level collection.
 *
 * The PSI reference contributor already drives Go-to-Declaration and Find Usages;
 * this inspection adds the missing diagnostic layer (JSON has no built-in
 * Unresolved-reference inspection, so resolution failures are otherwise silent).
 */
class WorkflowUnresolvedReferenceInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!WorkflowDoc.isWorkflowFile(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : JsonElementVisitor() {
            override fun visitStringLiteral(literal: JsonStringLiteral) {
                val kind = classifyReference(literal) ?: return
                val root = workflowRoot(literal) ?: return
                if (findDefinitionByName(root, kind.collection, literal.value) != null) return
                holder.registerProblem(
                    literal,
                    "Missing ${kind.display} definition: '${literal.value}'",
                    ProblemHighlightType.GENERIC_ERROR,
                    CreateMissingDefinitionFix(kind, literal.value),
                )
            }
        }
    }
}
