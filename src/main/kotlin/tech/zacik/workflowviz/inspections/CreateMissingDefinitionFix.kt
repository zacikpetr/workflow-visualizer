package tech.zacik.workflowviz.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import tech.zacik.workflowviz.refs.WorkflowReferenceKind

/**
 * Quick fix on a `WorkflowUnresolvedReference` problem — inserts a stub object
 * (`{ "name": "X", … }`) into the right top-level array (`states[]` /
 * `functions[]` / `events[]` / `errors[]`) so the reference resolves.
 *
 * The stub carries the **minimum** fields the spec needs for the workflow to
 * still parse:
 *  - state → `type: "operation"` + `end: true` (lands as a no-op terminal)
 *  - function / event / error → just the discriminating field with `""`
 *
 * We deliberately leave the stub fields empty rather than guessing values —
 * a wrong guess is harder to spot than a blank that demands user attention.
 * Re-formats the touched range so the inserted JSON matches the file's
 * existing indentation.
 */
class CreateMissingDefinitionFix(
    private val kind: WorkflowReferenceKind,
    private val name: String,
) : LocalQuickFix {

    // Family name groups quick fixes in Settings → Inspections; per-instance
    // name names the target collection ("errors") rather than the singular
    // ("error"), which avoids reading "Create error 'X'" as "make an error
    // happen". The verb "Add" emphasises a structural edit, not a runtime event.
    override fun getFamilyName(): String = "Add missing ${kind.display} definition"
    override fun getName(): String = "Add '$name' to ${kind.collection}"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? JsonStringLiteral ?: return
        val file = literal.containingFile ?: return
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return

        val stub = stubFor(kind, name)
        val arrayProp = root.findProperty(kind.collection)
        val insertOffset: Int
        val insertText: String
        if (arrayProp != null) {
            val array = arrayProp.value as? JsonArray ?: return
            insertOffset = array.textRange.endOffset - 1 // just before ']'
            insertText = if (array.valueList.isEmpty()) stub else ", $stub"
        } else {
            // Collection doesn't exist yet — add it as a new top-level property.
            insertOffset = root.textRange.endOffset - 1 // just before '}'
            insertText = ", \"${kind.collection}\": [$stub]"
        }

        val pdm = PsiDocumentManager.getInstance(project)
        val document = pdm.getDocument(file) ?: return
        document.insertString(insertOffset, insertText)
        pdm.commitDocument(document)

        // Run the IDE formatter over what we just wrote so it picks up the
        // surrounding indentation conventions instead of our flat one-liner.
        CodeStyleManager.getInstance(project)
            .reformatText(file, listOf(TextRange(insertOffset, insertOffset + insertText.length)))

        moveCaretIntoStub(project, file, name, kind)
    }

    /**
     * Caret on the inserted name so the user can immediately edit. We look up
     * by `name == [name]` and `collection == [kind].collection`, which is
     * unambiguous straight after the insert (the duplicate inspection would
     * fire otherwise).
     */
    private fun moveCaretIntoStub(project: Project, file: PsiFile, name: String, kind: WorkflowReferenceKind) {
        val editor: Editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return
        val array = root.findProperty(kind.collection)?.value as? JsonArray ?: return
        val newObj = array.valueList
            .filterIsInstance<JsonObject>()
            .firstOrNull { (it.findProperty("name")?.value as? JsonStringLiteral)?.value == name }
            ?: return
        val nameLiteral = newObj.findProperty("name")?.value ?: return
        // textRange.endOffset - 1 lands the caret just before the closing quote,
        // ready for the user to start typing the real name if they want to rename.
        editor.caretModel.moveToOffset(nameLiteral.textRange.endOffset - 1)
    }

    companion object {
        private fun stubFor(kind: WorkflowReferenceKind, name: String): String = when (kind) {
            WorkflowReferenceKind.STATE ->
                """{ "name": "$name", "type": "operation", "end": true }"""
            WorkflowReferenceKind.FUNCTION ->
                """{ "name": "$name", "operation": "" }"""
            WorkflowReferenceKind.EVENT ->
                """{ "name": "$name", "source": "", "type": "" }"""
            WorkflowReferenceKind.ERROR ->
                """{ "name": "$name", "code": "" }"""
        }
    }
}
