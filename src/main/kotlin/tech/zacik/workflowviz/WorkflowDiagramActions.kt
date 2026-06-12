package tech.zacik.workflowviz

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.swing.Icon

/**
 * Toolbar action factories for the Workflow Visualizer tool window.
 *
 * Actions take **suppliers** rather than direct strings — the toolbar is built
 * once at startup, but the diagram (SVG / PUML) changes every keystroke, so the
 * action must pull the latest value at click time. Each supplier returns
 * `null` when no diagram is rendered yet; `update()` mirrors that into a
 * disabled toolbar button.
 */

/**
 * Zoom the diagram in/out one step around the canvas centre. The tooltip names
 * the equivalent gestures (⌘/Ctrl+scroll, trackpad pinch) so the mouse/trackpad
 * affordances — which never show up in the keymap — stay discoverable. The
 * keyboard shortcut is registered on the tool window component by the caller.
 */
class ZoomInAction(private val panel: DiagramPanel) : AnAction(
    "Zoom In",
    "Zoom in (also ⌘/Ctrl+scroll, or pinch on a trackpad)",
    AllIcons.General.ZoomIn,
) {
    override fun actionPerformed(e: AnActionEvent) = panel.zoomIn()
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    companion object {
        // "+" is Shift+"=" on most layouts, so bind the bare "=" (no Shift
        // needed), the shifted "+" for anyone who presses it, and the numpad
        // "+". Kept OUT of AnAction.setShortcutSet (internal API) — the caller
        // registers these on the tool window component.
        val SHORTCUTS: CustomShortcutSet = zoomShortcuts("EQUALS", "shift EQUALS", "ADD")
    }
}

class ZoomOutAction(private val panel: DiagramPanel) : AnAction(
    "Zoom Out",
    "Zoom out (also ⌘/Ctrl+scroll, or pinch on a trackpad)",
    AllIcons.General.ZoomOut,
) {
    override fun actionPerformed(e: AnActionEvent) = panel.zoomOut()
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    companion object {
        val SHORTCUTS: CustomShortcutSet = zoomShortcuts("MINUS", "SUBTRACT")
    }
}

/** Each key with Ctrl everywhere, plus the native ⌘ variant on macOS. */
private fun zoomShortcuts(vararg keys: String): CustomShortcutSet {
    val modifiers = if (SystemInfo.isMac) listOf("meta", "control") else listOf("control")
    return CustomShortcutSet.fromString(
        *modifiers.flatMap { mod -> keys.map { "$mod $it" } }.toTypedArray(),
    )
}

/** Snap zoom/pan back to "fit the whole diagram" without changing the document. */
class FitToWindowAction(private val panel: DiagramPanel) : AnAction(
    "Fit to Window",
    "Reset zoom and pan so the whole diagram fits the viewport",
    AllIcons.General.FitContent,
) {
    override fun actionPerformed(e: AnActionEvent) = panel.fitToWindow()
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** Save the rendered SVG of the current diagram to a file. */
class ExportSvgAction(private val svgSupplier: () -> String?) : ExportFileAction(
    text = "Export as SVG…",
    description = "Save the current diagram as an SVG file",
    icon = AllIcons.ToolbarDecorator.Export,
    defaultFileName = "diagram.svg",
    extension = "svg",
    saveDialogTitle = "Export Diagram as SVG",
    contentSupplier = svgSupplier,
)

/** Save the underlying PlantUML source of the current diagram to a file. */
class ExportPumlAction(private val pumlSupplier: () -> String?) : ExportFileAction(
    text = "Export as PUML…",
    description = "Save the PlantUML source of the current diagram to a file",
    icon = AllIcons.ToolbarDecorator.Export,
    defaultFileName = "diagram.puml",
    extension = "puml",
    saveDialogTitle = "Export Diagram as PUML",
    contentSupplier = pumlSupplier,
)

/**
 * Shared scaffolding for "export current diagram → save dialog → write file".
 * Lives here (not duplicated per action) because SVG and PUML differ only in
 * extension, file name, and which buffer is read.
 */
abstract class ExportFileAction(
    text: String,
    description: String,
    icon: Icon,
    private val defaultFileName: String,
    private val extension: String,
    private val saveDialogTitle: String,
    private val contentSupplier: () -> String?,
) : AnAction(text, description, icon) {

    final override fun actionPerformed(e: AnActionEvent) {
        val content = contentSupplier() ?: return
        val file = chooseFile(e.project) ?: return
        file.writeText(content)
    }

    final override fun update(e: AnActionEvent) {
        // Disable the button until there's actually something to export.
        e.presentation.isEnabled = contentSupplier() != null
    }

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun chooseFile(project: Project?): File? {
        val descriptor = FileSaverDescriptor(saveDialogTitle, "", extension)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        return dialog.save(null as VirtualFile?, defaultFileName)?.file
    }
}
