package tech.zacik.workflowviz

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout
import tech.zacik.workflowviz.settings.WorkflowVizSettings
import tech.zacik.workflowviz.settings.WorkflowVizSettingsListener

class WorkflowDiagramToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = WorkflowDiagramController(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(controller.component, "", false)
        toolWindow.contentManager.addContent(content)
        controller.start()
    }
}

/**
 * Wires the diagram panel to the active .sw.json editor:
 *  - live re-render on document change,
 *  - click a state in the diagram → move the caret to its definition,
 *  - caret in the JSON → highlight the enclosing state in the diagram.
 */
class WorkflowDiagramController(
    private val project: Project,
    parentDisposable: Disposable,
) : Disposable {

    private val content = JPanel(BorderLayout())
    // `also` (vs `apply`): inside `apply`, `content` would resolve to
    // SimpleToolWindowPanel.getContent() (nullable) and clash with our field.
    private val root = SimpleToolWindowPanel(true, true).also { it.setContent(content) }
    private val hint = JLabel("Open a .sw.json file", JLabel.CENTER)
    private val panel = DiagramPanel(::onStateClicked)
    private val renderAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val locateAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private var editor: Editor? = null
    private var editorListeners: Disposable? = null
    private var lastLocated: String? = null
    /** Last successfully rendered SVG / PUML — fed to the Export actions. */
    private var lastSvg: String? = null
    private var lastPuml: String? = null

    val component: JComponent get() = root

    init {
        Disposer.register(parentDisposable, this)
        content.add(hint, BorderLayout.CENTER)
        root.setToolbar(buildToolbar())
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(FitToWindowAction(panel))
            addSeparator()
            add(ExportSvgAction { lastSvg })
            add(ExportPumlAction { lastPuml })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "WorkflowDiagramToolbar",
            group,
            true,
        )
        // Without targetComponent, the toolbar can't resolve the data context
        // and Export's update() runs against a stale presentation.
        toolbar.targetComponent = root
        return toolbar.component
    }

    fun start() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) =
                    attachToSelectedEditor()
            },
        )
        // Live-update on Settings Apply — badges / colors / label length toggles
        // re-render without waiting for the next document edit.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            WorkflowVizSettingsListener.TOPIC,
            WorkflowVizSettingsListener { scheduleRender() },
        )
        attachToSelectedEditor()
    }

    private fun attachToSelectedEditor() {
        editorListeners?.let { Disposer.dispose(it) }
        editorListeners = null
        editor = FileEditorManager.getInstance(project).selectedTextEditor

        val ed = editor
        if (ed == null) {
            showHint()
            return
        }
        val listeners = Disposer.newDisposable("wfviz-editor")
        Disposer.register(this, listeners)
        editorListeners = listeners

        ed.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(e: DocumentEvent) = scheduleRender()
        }, listeners)
        ed.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) = scheduleLocate()
        }, listeners)

        scheduleRender()
    }

    private fun showHint() {
        ApplicationManager.getApplication().invokeLater {
            content.removeAll(); content.add(hint, BorderLayout.CENTER); content.revalidate(); content.repaint()
        }
    }

    private fun showPanel() {
        if (panel.parent !== content) {
            ApplicationManager.getApplication().invokeLater {
                content.removeAll(); content.add(panel, BorderLayout.CENTER); content.revalidate(); content.repaint()
            }
        }
    }

    private fun scheduleRender() {
        val text = editor?.document?.text ?: return
        renderAlarm.cancelAllRequests()
        renderAlarm.addRequest({ render(text) }, 250)
    }

    private fun scheduleLocate() {
        val ed = editor ?: return
        val text = ed.document.text
        val caret = ed.caretModel.offset
        locateAlarm.cancelAllRequests()
        locateAlarm.addRequest({
            val names = WorkflowJson.parse(text)?.let { WorkflowJson.stateNames(it) } ?: return@addRequest
            val state = JsonStateOffsets.enclosingState(text, caret, names)
            if (state != lastLocated) {
                lastLocated = state
                // In-place DOM patch — no PlantUML re-render. Fast on large diagrams.
                panel.setLocate(state)
            }
        }, 200)
    }

    /**
     * Parse + generate + render off the EDT, then swap the SVG on the EDT.
     * The SVG is rendered without locate emphasis — the located state (if any)
     * is re-applied by the panel after the new GVT tree is built.
     */
    private fun render(text: String) {
        val workflow = WorkflowJson.parse(text) ?: run { showHint(); return }
        val service = WorkflowVizSettings.getInstance()
        val settings = service.state
        val config = SwJsonToPuml.Config(
            labelMaxChars = settings.labelMaxChars,
            colorErrorEdges = settings.colorErrorEdges,
            showMutationBadges = settings.mutationBadgesEnabled,
            mutationBadgeFields = service.mutationFieldSet(),
            dimUnreachable = settings.dimUnreachable,
        )
        val puml = SwJsonToPuml.toPuml(workflow, config = config)
        val svg = try {
            PlantUmlRenderer.toSvg(puml)
        } catch (e: Exception) {
            return // keep last good render
        }
        lastPuml = puml
        lastSvg = svg
        ApplicationManager.getApplication().invokeLater {
            showPanel()
            panel.setSvg(svg)
        }
    }

    /** Diagram → editor: move the caret to the clicked state's definition. */
    private fun onStateClicked(stateName: String) {
        val ed = editor ?: return
        val file = FileDocumentManager.getInstance().getFile(ed.document) ?: return
        val offset = JsonStateOffsets.offsetOfState(ed.document.text, stateName)
        if (offset < 0) return
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(file, true)
            ed.caretModel.moveToOffset(offset)
            ed.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    override fun dispose() {}
}
