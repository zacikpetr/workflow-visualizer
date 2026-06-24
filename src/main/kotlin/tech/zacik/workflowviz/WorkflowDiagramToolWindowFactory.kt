package tech.zacik.workflowviz

import com.intellij.json.psi.JsonFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
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
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.GotItTooltip
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout
import tech.zacik.workflowviz.settings.PathHighlightListener
import tech.zacik.workflowviz.settings.WorkflowVizSettings
import tech.zacik.workflowviz.settings.WorkflowVizSettingsListener

class WorkflowDiagramToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = WorkflowDiagramController(project, toolWindow)
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
    private val toolWindow: ToolWindow,
) : Disposable {

    private val content = JPanel(BorderLayout())
    // `also` (vs `apply`): inside `apply`, `content` would resolve to
    // SimpleToolWindowPanel.getContent() (nullable) and clash with our field.
    private val root = SimpleToolWindowPanel(true, true).also { it.setContent(content) }
    private val hint = JLabel("Open a .sw.json file", JLabel.CENTER)
    private val panel = DiagramPanel(::onStateClicked)
    // Controller-scoped coroutines, cancelled in dispose(). No dispatcher in the
    // context → launches default to Dispatchers.Default (background); UI work
    // hops to Dispatchers.EDT explicitly. SupervisorJob so one failed render
    // doesn't tear down the scope.
    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("wfviz-diagram"))
    // Single-lane dispatcher for PlantUML: a cancel() can't interrupt a render
    // already inside the CPU-bound engine, so without the lane a burst of edits
    // stacks concurrent multi-second renders on the shared Default pool.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderLane = Dispatchers.Default.limitedParallelism(1)
    // Locate runs on its own single lane so its jobs serialize against each other
    // (lastLocated has no other synchronization) without ever queueing behind a
    // multi-second PlantUML render.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val locateLane = Dispatchers.Default.limitedParallelism(1)
    private var renderJob: Job? = null
    private var locateJob: Job? = null
    /** Set when an edit arrives while the tool window is hidden — rendered on re-show. */
    private var pendingRender = false

    // Written on the EDT, read from background coroutines.
    @Volatile
    private var editor: Editor? = null
    private var editorListeners: Disposable? = null
    // Written on the EDT (reset on editor switch) and on the locate lane.
    @Volatile
    private var lastLocated: String? = null
    /** Last successfully rendered SVG / PUML — fed to the Export actions. */
    @Volatile
    private var lastSvg: String? = null
    @Volatile
    private var lastPuml: String? = null

    val component: JComponent get() = root

    init {
        Disposer.register(toolWindow.disposable, this)
        content.add(hint, BorderLayout.CENTER)
        root.setToolbar(buildToolbar())
    }

    private fun buildToolbar(): JComponent {
        val zoomIn = ZoomInAction(panel)
        val zoomOut = ZoomOutAction(panel)
        val highlightPath = HighlightPathAction()
        val group = DefaultActionGroup().apply {
            add(zoomIn)
            add(zoomOut)
            add(FitToWindowAction(panel))
            addSeparator()
            add(highlightPath)
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
        // Bind the zoom shortcuts to the tool window component so they fire while
        // it holds focus (a code-built action has no keymap entry of its own).
        zoomIn.registerCustomShortcutSet(ZoomInAction.SHORTCUTS, root, this)
        zoomOut.registerCustomShortcutSet(ZoomOutAction.SHORTCUTS, root, this)
        highlightPath.registerCustomShortcutSet(HighlightPathAction.SHORTCUTS, root, this)
        // Honour the persisted focus-mode toggle on a freshly opened tool window.
        panel.setPathHighlight(WorkflowVizSettings.getInstance().state.highlightSelectedPath)
        return toolbar.component
    }

    /**
     * One-time onboarding bubble teaching the navigation gestures — they're
     * mouse/trackpad only, so they appear in no keymap or menu. [GotItTooltip]
     * persists its "seen" state by id, so this shows once per user and then never
     * again, even though [showPanel] may run repeatedly.
     */
    private fun showGesturesGotIt() {
        GotItTooltip(
            "workflowviz.diagram.gestures",
            "Scroll to pan · ⌘/Ctrl+scroll or pinch to zoom · drag to move · " +
                "click a state to jump to its definition",
            this,
        )
            .withHeader("Navigating the diagram")
            .show(root, GotItTooltip.TOP_MIDDLE)
    }

    fun start() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) =
                    attachToSelectedEditor()
            },
        )
        // Edits made while the tool window is hidden don't render (see
        // scheduleRender); catch up once it becomes visible again.
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id == toolWindow.id && pendingRender) {
                        pendingRender = false
                        scheduleRender()
                    }
                }
            },
        )
        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        // Live-update on Settings Apply — badges / colors / label length toggles
        // re-render without waiting for the next document edit.
        appBus.subscribe(
            WorkflowVizSettingsListener.TOPIC,
            WorkflowVizSettingsListener { scheduleRender() },
        )
        // Focus-mode toggle: converge this panel on the shared global flag via a
        // cheap DOM patch (no re-render), so a toggle in any project syncs here.
        appBus.subscribe(
            PathHighlightListener.TOPIC,
            PathHighlightListener {
                panel.setPathHighlight(WorkflowVizSettings.getInstance().state.highlightSelectedPath)
            },
        )
        attachToSelectedEditor()
    }

    private fun attachToSelectedEditor() {
        editorListeners?.let { Disposer.dispose(it) }
        editorListeners = null
        // In-flight jobs belong to the previous editor — a late render commit
        // would repaint the old file's diagram over the hint, and a late
        // locate would re-set the highlight cleared below.
        renderJob?.cancel()
        locateJob?.cancel()
        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        val file = selected?.let { FileDocumentManager.getInstance().getFile(it.document) }
        // Only JSON files can be workflows — installing listeners on every
        // editor would copy + Gson-parse the whole document after each
        // keystroke in any file type.
        editor = if (file != null && file.name.endsWith(".json")) selected else null

        // Locate state belongs to the previous editor: without the reset, a
        // same-named state in the new file is skipped as "already highlighted"
        // and the diagram never lights up until something else changes.
        lastLocated = null
        panel.setLocate(null)

        val ed = editor
        if (ed == null) {
            lastSvg = null
            lastPuml = null
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

    /** EDT only — callers from coroutines must wrap in withContext(Dispatchers.EDT). */
    private fun showHint() {
        content.removeAll(); content.add(hint, BorderLayout.CENTER); content.revalidate(); content.repaint()
    }

    /** EDT only — callers from coroutines must wrap in withContext(Dispatchers.EDT). */
    private fun showPanel() {
        if (panel.parent !== content) {
            content.removeAll(); content.add(panel, BorderLayout.CENTER); content.revalidate(); content.repaint()
            showGesturesGotIt()
        }
    }

    private fun scheduleRender() {
        val ed = editor ?: return
        // Hidden tool window → every edit would still cost a full parse + PUML
        // generation + PlantUML render nobody sees. Defer to the next show.
        if (!toolWindow.isVisible) {
            pendingRender = true
            return
        }
        renderJob?.cancel()
        // delay() acts as the debounce: a newer edit cancels the pending job
        // before it elapses. The document text is captured *after* the debounce
        // window inside a read action — copying it eagerly here would cost an
        // O(document) string copy per keystroke.
        renderJob = scope.launch(renderLane) {
            delay(250)
            val text = readAction { if (ed.isDisposed) null else ed.document.text } ?: return@launch
            render(text)
        }
    }

    private fun scheduleLocate() {
        val ed = editor ?: return
        val caret = ed.caretModel.offset // caret listener runs on the EDT
        locateJob?.cancel()
        locateJob = scope.launch(locateLane) {
            delay(200)
            // Enclosing state from the JSON PSI — whole-object granularity; a caret
            // outside any flow node resolves to null and clears the highlight.
            val state = readAction {
                if (ed.isDisposed) return@readAction null
                val psi = PsiDocumentManager.getInstance(project).getPsiFile(ed.document) as? JsonFile
                    ?: return@readAction null
                WorkflowStateNavigation.enclosingStateName(psi, caret)
            }
            if (state != lastLocated) {
                lastLocated = state
                // In-place DOM patch — no PlantUML re-render. Fast on large diagrams.
                panel.setLocate(state)
            }
        }
    }

    /**
     * Parse + generate + render off the EDT, then swap the SVG on the EDT.
     * The SVG is rendered without locate emphasis — the located state (if any)
     * is re-applied by the panel after the new GVT tree is built.
     */
    private suspend fun render(text: String) {
        val workflow = WorkflowJson.parse(text)
        if (workflow == null) {
            // Transiently invalid JSON while typing — keep the last good
            // diagram visible; only fall back to the hint when nothing has
            // been rendered for this editor yet.
            if (lastSvg == null) withContext(Dispatchers.EDT) { showHint() }
            return
        }
        val service = WorkflowVizSettings.getInstance()
        val settings = service.state
        val config = SwJsonToPuml.Config(
            labelMaxChars = settings.labelMaxChars,
            colorErrorEdges = settings.colorErrorEdges,
            showMutationBadges = settings.mutationBadgesEnabled,
            mutationBadgeFields = service.mutationFieldSet(),
            dimUnreachable = settings.dimUnreachable,
        )
        val result = SwJsonToPuml.toPumlResult(workflow, config = config)
        val puml = result.puml
        if (puml == lastPuml) return // no-op edit (whitespace, comments) — skip the engine
        val svg = try {
            PlantUmlRenderer.toSvg(puml)
        } catch (e: Exception) {
            LOG.warn("PlantUML render failed — keeping last good diagram", e)
            return
        }
        // Parse here, off the EDT — the XML parse is O(SVG size) and used to
        // jank the UI thread on every re-render of a large diagram.
        val doc = try {
            DiagramPanel.parseSvg(svg)
        } catch (e: Exception) {
            LOG.warn("SVG parse failed — keeping last good diagram", e)
            return
        }
        withContext(Dispatchers.EDT) {
            // Committed on the EDT: a stale render cancelled meanwhile never
            // gets here, so the Export actions always match what's on screen.
            lastPuml = puml
            lastSvg = svg
            showPanel()
            panel.setSvg(doc, result.nameToAlias)
        }
    }

    /**
     * Diagram → editor: move the caret to the clicked state's definition. Batik
     * fires link events on its own update thread, so we re-dispatch to the EDT;
     * the offset is resolved under a read action and the caret move runs on the EDT.
     */
    private fun onStateClicked(stateName: String) {
        scope.launch(Dispatchers.EDT) {
            val ed = editor ?: return@launch
            if (ed.isDisposed) return@launch
            val file = FileDocumentManager.getInstance().getFile(ed.document) ?: return@launch
            // Same PSI index as Ctrl+B → the state definition, not a like-named
            // function/action earlier in the file.
            val offset = readAction {
                if (ed.isDisposed) return@readAction null
                val psi = PsiDocumentManager.getInstance(project).getPsiFile(ed.document) as? JsonFile
                    ?: return@readAction null
                WorkflowStateNavigation.stateNameOffset(psi, stateName)
            } ?: return@launch
            FileEditorManager.getInstance(project).openFile(file, true)
            ed.caretModel.moveToOffset(offset)
            ed.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    override fun dispose() {
        scope.cancel()
        panel.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(WorkflowDiagramController::class.java)
    }
}
