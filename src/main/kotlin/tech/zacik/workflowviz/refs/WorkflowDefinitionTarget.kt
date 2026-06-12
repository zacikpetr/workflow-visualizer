package tech.zacik.workflowviz.refs

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.pom.PomDeclarationSearcher
import com.intellij.pom.PomRenameableTarget
import com.intellij.pom.PomTarget
import com.intellij.pom.references.PomService
import com.intellij.psi.DelegatePsiTarget
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer

/**
 * POM target wrapping a definition's `name` literal (`states[].name` etc.).
 *
 * [WorkflowReference.resolve] returns the PSI form of this target instead of
 * the literal itself: `JsonStringLiteral` is not a `PsiNamedElement`, so
 * resolving straight to it makes the platform refuse Rename ("cannot perform
 * refactoring"). The wrapper exposes name/setName — rename rewrites this
 * literal's content, and the usages follow through each reference's
 * `handleElementRename` — while navigation still lands on the literal.
 *
 * Equality is inherited from [DelegatePsiTarget] (same class + same literal),
 * which makes [PomService.convertToPsi] hand out equivalent PSI elements for
 * repeated resolves — required for `isReferenceTo` / usage search to match.
 */
class WorkflowDefinitionTarget(private val literal: JsonStringLiteral) :
    DelegatePsiTarget(literal), PomRenameableTarget<Any?> {

    override fun getName(): String = literal.value

    override fun isWritable(): Boolean = literal.isWritable

    override fun setName(newName: String): Any? {
        ElementManipulators.handleContentChange(literal, newName)
        return null
    }

    companion object {
        fun psiFor(literal: JsonStringLiteral): PsiElement =
            PomService.convertToPsi(literal.project, WorkflowDefinitionTarget(literal))
    }
}

/**
 * Lets actions started **on the definition itself** (caret on `states[].name`)
 * see the same POM target the references resolve to. Without this, Rename and
 * Find Usages only work when invoked from the usage side.
 */
class WorkflowPomDeclarationSearcher : PomDeclarationSearcher() {
    override fun findDeclarationsAt(element: PsiElement, offsetInElement: Int, consumer: Consumer<in PomTarget>) {
        val literal = PsiTreeUtil.getParentOfType(element, JsonStringLiteral::class.java, false) ?: return
        if (definitionKindOf(literal) == null) return
        consumer.consume(WorkflowDefinitionTarget(literal))
    }
}

/**
 * If [literal] is the `name` value of a definition in one of the four
 * top-level collections, returns that collection's kind; null otherwise.
 */
fun definitionKindOf(literal: JsonStringLiteral): WorkflowReferenceKind? {
    val nameProp = literal.parent as? JsonProperty ?: return null
    if (nameProp.name != "name" || nameProp.value !== literal) return null
    val defObject = nameProp.parent as? JsonObject ?: return null
    val array = defObject.parent as? JsonArray ?: return null
    val collectionProperty = array.parent as? JsonProperty ?: return null
    return WorkflowReferenceKind.values().firstOrNull { it.collection == collectionProperty.name }
}
