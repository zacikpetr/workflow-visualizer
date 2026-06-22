package tech.zacik.workflowviz

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import tech.zacik.workflowviz.inspections.WorkflowDoc
import tech.zacik.workflowviz.refs.WorkflowIndex
import tech.zacik.workflowviz.refs.WorkflowReferenceKind

/**
 * Structural (JSON PSI) mapping between editor offsets and diagram states:
 * [enclosingStateName] (caret → diagram) and [stateNameOffset] (diagram → caret).
 * The whole state object is the unit, and only real `states[]` elements count —
 * the same definitions Ctrl+B resolves to.
 */
object WorkflowStateNavigation {

    /**
     * State whose object encloses [offset], or null when the caret is outside any
     * `states[]` element (function/event definition, metadata, whitespace). A
     * caret on a nested action/functionRef resolves to its enclosing state.
     */
    fun enclosingStateName(file: JsonFile, offset: Int): String? {
        val root = WorkflowDoc.rootOf(file) ?: return null
        val statesArray = WorkflowDoc.arrayOf(root, "states") ?: return null
        // The state is the enclosing object that's a direct element of states[].
        var obj = PsiTreeUtil.getParentOfType(file.findElementAt(offset), JsonObject::class.java, false)
        while (obj != null) {
            if (obj.parent === statesArray) return WorkflowDoc.nameOf(obj)
            obj = PsiTreeUtil.getParentOfType(obj, JsonObject::class.java, true)
        }
        return null
    }

    /**
     * Caret offset just inside the opening quote of the state's `"name"`, or null
     * if no such state. Via the cached index, so it never lands on a like-named
     * function/action earlier in the file.
     */
    fun stateNameOffset(file: JsonFile, name: String): Int? {
        val def = WorkflowIndex.findDefinition(file, WorkflowReferenceKind.STATE, name) ?: return null
        val nameLiteral = WorkflowDoc.nameLiteralOf(def) ?: return null
        return nameLiteral.textRange.startOffset + 1
    }
}
