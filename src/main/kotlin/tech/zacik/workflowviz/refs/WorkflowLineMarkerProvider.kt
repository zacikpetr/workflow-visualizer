package tech.zacik.workflowviz.refs

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Gutter icon on each definition (`"name": "X"` inside states/functions/events/errors[])
 * showing the count of usages and navigating to them on click.
 *
 * Implementation note: IntelliJ requires line marker providers to run on **leaf** PSI
 * elements (to avoid the "non-leaf marker" warning). We add the marker on the first
 * leaf of the JsonStringLiteral that is the value of a `"name"` property.
 */
class WorkflowLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val literal = element.parent as? JsonStringLiteral ?: return
        // Only the first leaf of the literal contributes the marker (avoid duplicates).
        if (literal.firstChild !== element) return

        val kind = definitionKindOf(literal) ?: return
        val name = literal.value
        val usages = findUsages(literal.containingFile, kind, name)
        if (usages.isEmpty()) return

        val marker = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
            .setTargets(usages)
            .setTooltipText("${usages.size} usage${if (usages.size == 1) "" else "s"} of this ${kind.display}")
            .setPopupTitle("Usages of \"$name\"")
            .setCellRenderer { WorkflowUsageRenderer() }
            .createLineMarkerInfo(element)
        result.add(marker)
    }
}

/** All literals in [file] that classify as references of [kind] with value [name]. */
private fun findUsages(file: PsiFile, kind: WorkflowReferenceKind, name: String): List<PsiElement> =
    WorkflowIndex.findUsages(file, kind, name)
