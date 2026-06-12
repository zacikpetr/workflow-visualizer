package tech.zacik.workflowviz

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.UpdateManager
import org.apache.batik.bridge.UpdateManagerAdapter
import org.apache.batik.bridge.UpdateManagerEvent
import org.apache.batik.bridge.UpdateManagerListener
import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.swing.svg.JSVGComponent
import org.apache.batik.swing.svg.SVGUserAgentAdapter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.Element
import org.w3c.dom.svg.SVGDocument
import tech.zacik.workflowviz.settings.WorkflowVizSettings
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.io.StringReader
import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Batik-based SVG viewer for the rendered PlantUML diagram.
 *
 * Batik's built-in interactors have unintuitive triggers, so we drive the
 * rendering transform ourselves: **scroll = pan**, **⌘/Ctrl + scroll = zoom at
 * cursor**, **trackpad pinch = zoom** (macOS), **left-drag = pan**. Plain scroll
 * panning means a trackpad two-finger swipe moves the diagram instead of zooming
 * it. Batik re-renders the vector on zoom → crisp at any scale. Clicking a
 * state anchor (`<a href="swjson://X">`) fires [onStateClicked]; default link
 * navigation is suppressed via the user agent.
 */
class DiagramPanel(private val onStateClicked: (String) -> Unit) : JPanel(BorderLayout()) {

    private val userAgent = object : SVGUserAgentAdapter() {
        override fun openLink(uri: String?, newc: Boolean) { /* handled via listener */ }
    }

    private val canvas = JSVGCanvas(userAgent, true, true).apply {
        // Use our own zoom/pan instead of Batik's interactors.
        setEnableZoomInteractor(false)
        setEnablePanInteractor(false)
        setEnableRotateInteractor(false)
        setEnableImageZoomInteractor(false)
        setEnableResetTransformInteractor(false)
        setDoubleBufferedRendering(true)
        // Dynamic mode → DOM patches (setLocate) trigger an incremental re-render
        // of only the affected node instead of a full repaint.
        setDocumentState(JSVGComponent.ALWAYS_DYNAMIC)
        addLinkActivationListener { e ->
            val uri = e.referencedURI ?: return@addLinkActivationListener
            if (uri.startsWith(StateUri.SCHEME) && uri != DOC_URI) onStateClicked(StateUri.decode(uri))
        }
    }

    private var dragStart: Point? = null
    /** Last known cursor position over the canvas — pinch's fixed zoom point. */
    private var lastMousePoint: Point? = null

    // ── locate (in-place SVG DOM patch — avoids re-rendering the whole diagram) ──
    // Volatile: written on the EDT (setSvg clears) and on Batik's update queue
    // thread (applyLocate); restores are additionally guarded by a document
    // identity check so a patch never lands on a swapped-out document.
    /** Name of the currently located state, re-applied after each setSvg. */
    @Volatile
    private var currentLocate: String? = null
    /** The shape element we re-coloured, plus its original fill (or null if none). */
    @Volatile
    private var locatedShape: Element? = null
    @Volatile
    private var locatedOriginalFill: String? = null

    /**
     * Zoom-restore listener from the latest [setSvg]. Tracked so a newer swap
     * removes a predecessor that never fired (document replaced before its
     * UpdateManager started) — otherwise the stale listener fires on the *next*
     * document and restores an outdated transform.
     */
    @Volatile
    private var restoreListener: UpdateManagerListener? = null

    init {
        add(canvas, BorderLayout.CENTER)
        wireZoomAndPan()
        installPinchToZoom()
    }

    private fun wireZoomAndPan() {
        canvas.addMouseWheelListener { e: MouseWheelEvent ->
            lastMousePoint = e.point
            // ⌘/Ctrl + scroll → zoom at cursor (works the same for mouse and
            // trackpad). Plain scroll → pan, so a trackpad two-finger swipe moves
            // the diagram rather than zooming it. preciseWheelRotation keeps
            // trackpad steps smooth instead of snapping to whole notches.
            if (e.isMetaDown || e.isControlDown) {
                zoomAt(Math.pow(ZOOM_STEP, -e.preciseWheelRotation), e.x, e.y)
            } else {
                val delta = e.preciseWheelRotation * PAN_STEP
                // macOS/JBR reports horizontal trackpad scroll as a shifted wheel.
                if (e.isShiftDown) panBy(-delta, 0.0) else panBy(0.0, -delta)
            }
        }
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) dragStart = e.point
            }
            override fun mouseReleased(e: MouseEvent) { dragStart = null }
        })
        canvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { lastMousePoint = e.point }
            override fun mouseDragged(e: MouseEvent) {
                lastMousePoint = e.point
                val start = dragStart ?: return
                panBy((e.x - start.x).toDouble(), (e.y - start.y).toDouble())
                dragStart = e.point
            }
        })
    }

    /**
     * macOS trackpad pinch → zoom, via the JBR-bundled Apple gesture API
     * (`com.apple.eawt.event`). Called through reflection + a dynamic [Proxy] so
     * we need no `--add-exports` build flag and degrade to a no-op on non-mac or
     * non-JBR runtimes — where ⌘/Ctrl + scroll still zooms. The magnification
     * event carries no coordinates, so we anchor the zoom at the last cursor
     * position over the canvas, matching the wheel-zoom fixed point.
     */
    private fun installPinchToZoom() {
        if (!SystemInfo.isMac) return
        try {
            val gestureUtilities = Class.forName("com.apple.eawt.event.GestureUtilities")
            val gestureListener = Class.forName("com.apple.eawt.event.GestureListener")
            val magnificationListener = Class.forName("com.apple.eawt.event.MagnificationListener")
            val getMagnification =
                Class.forName("com.apple.eawt.event.MagnificationEvent").getMethod("getMagnification")

            val listener = Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(magnificationListener),
            ) { proxy, method, args ->
                when (method.name) {
                    "magnify" -> {
                        val magnification = getMagnification.invoke(args[0]) as Double
                        // Fall back to the canvas centre if the cursor hasn't moved
                        // over the canvas yet (first gesture) — otherwise it no-ops.
                        val p = lastMousePoint ?: Point(canvas.width / 2, canvas.height / 2)
                        // magnification is a per-event delta (e.g. 0.03); the step
                        // scale factor is 1 + delta → spread = in, pinch = out.
                        if (magnification != 0.0) zoomAt(1.0 + magnification, p.x, p.y)
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    "toString" -> "PinchToZoomListener"
                    else -> null
                }
            }
            // Signature is addGestureListenerTo(JComponent, GestureListener) — using
            // Component here throws NoSuchMethodException, silently killing pinch.
            gestureUtilities
                .getMethod("addGestureListenerTo", JComponent::class.java, gestureListener)
                .invoke(null, canvas, listener)
        } catch (t: Throwable) {
            // Gesture API absent (non-JBR runtime) — ⌘/Ctrl + scroll still zooms.
            // Logged (not swallowed) so a wrong reflective signature is visible in
            // idea.log instead of silently disabling pinch.
            LOG.info("Pinch-to-zoom unavailable; falling back to ⌘/Ctrl+scroll", t)
        }
    }

    /** Translate the view by ([dx], [dy]) screen pixels, preserving zoom. */
    private fun panBy(dx: Double, dy: Double) {
        val cur = canvas.renderingTransform ?: return
        val pan = AffineTransform.getTranslateInstance(dx, dy)
        pan.concatenate(cur)
        canvas.setRenderingTransform(pan, true)
    }

    /**
     * Zoom by [factor] keeping the point (x,y) under the cursor fixed. The
     * resulting scale is clamped — Batik re-rasterizes the full vector at the
     * new scale, so runaway zoom means huge tile renders (CPU) or an invisible
     * diagram.
     */
    private fun zoomAt(factor: Double, x: Int, y: Int) {
        val cur = canvas.renderingTransform ?: return
        val clamped = when {
            cur.scaleX * factor > MAX_SCALE -> MAX_SCALE / cur.scaleX
            cur.scaleX * factor < MIN_SCALE -> MIN_SCALE / cur.scaleX
            else -> factor
        }
        if (clamped == 1.0) return
        val at = AffineTransform()
        at.translate(x.toDouble(), y.toDouble())
        at.scale(clamped, clamped)
        at.translate(-x.toDouble(), -y.toDouble())
        at.concatenate(cur)
        canvas.setRenderingTransform(at, true)
    }

    /**
     * Replace the displayed diagram with a document from [parseSvg]. Call on
     * the EDT (parsing itself belongs on a background thread — it's an
     * O(SVG-size) XML parse).
     *
     * Preserves the current zoom/pan across the swap **only when zoomed in
     * (scale ≥ 1.0)** — i.e. you're inspecting detail and don't want re-renders
     * (locate, edit) yanking the view back to fit. When zoomed out (scale <
     * 1.0, the typical "overview" state) we let Batik auto-fit the new SVG
     * so a freshly grown diagram stays fully visible. First load = always fit.
     */
    fun setSvg(doc: SVGDocument) {
        val previousRT = canvas.renderingTransform
        val preserve = previousRT != null && previousRT.scaleX >= 1.0

        // A restore listener whose document got replaced before its manager
        // started would otherwise fire on *this* document with an outdated
        // transform.
        restoreListener?.let { canvas.removeUpdateManagerListener(it) }
        restoreListener = null
        // Tracking state belongs to the old document; clear it before swap.
        locatedShape = null
        locatedOriginalFill = null
        if (preserve) {
            // Batik internally calls setRenderingTransform(initialTransform)
            // *between* gvtBuildCompleted and managerStarted (after recomputing
            // the document fit), so restoring earlier gets overwritten one tick
            // later. managerStarted fires after that recompute — the right
            // hook. Fires on Batik's RunnableQueue thread → dispatch to EDT.
            val listener = object : UpdateManagerAdapter() {
                override fun managerStarted(e: UpdateManagerEvent) {
                    canvas.removeUpdateManagerListener(this)
                    if (restoreListener === this) restoreListener = null
                    SwingUtilities.invokeLater {
                        canvas.setRenderingTransform(previousRT, true)
                    }
                }
            }
            restoreListener = listener
            canvas.addUpdateManagerListener(listener)
        }
        canvas.setSVGDocument(doc)
        // Re-apply current locate via the update queue — runs once UpdateManager
        // is alive for the new document, so the patch triggers an auto-repaint.
        if (currentLocate != null) inUpdateQueue { applyLocate(currentLocate) }
    }

    /**
     * Reset zoom and pan so Batik's auto-fit takes over — the whole diagram
     * fits the viewport. Equivalent to clicking Fit on the toolbar.
     */
    fun fitToWindow() {
        canvas.setRenderingTransform(AffineTransform(), true)
    }

    /** Zoom in one step, anchored at the canvas centre (toolbar button / keyboard). */
    fun zoomIn() = zoomAt(ZOOM_BUTTON_STEP, canvas.width / 2, canvas.height / 2)

    /** Zoom out one step, anchored at the canvas centre. */
    fun zoomOut() = zoomAt(1.0 / ZOOM_BUTTON_STEP, canvas.width / 2, canvas.height / 2)

    /**
     * Highlight the state called [stateName] in the live SVG by patching its
     * `<rect>` fill — **no PlantUML re-render**, so caret-driven locate is fast
     * even on huge diagrams. Pass `null` to clear.
     */
    fun setLocate(stateName: String?) {
        currentLocate = stateName
        inUpdateQueue { applyLocate(stateName) }
    }

    /**
     * Run a DOM-touching task on Batik's `RunnableQueue`. If the canvas's update
     * manager isn't alive yet (page mid-load), defer until `managerStarted`.
     * Without this, attribute mutations don't trigger a repaint until something
     * else nudges the canvas (mouse motion).
     */
    private fun inUpdateQueue(task: () -> Unit) {
        val manager = canvas.updateManager
        if (manager != null) {
            manager.updateRunnableQueue.invokeLater(task)
            return
        }
        canvas.addUpdateManagerListener(object : UpdateManagerAdapter() {
            override fun managerStarted(e: UpdateManagerEvent) {
                canvas.removeUpdateManagerListener(this)
                (e.source as UpdateManager).updateRunnableQueue.invokeLater(task)
            }
        })
    }

    private fun applyLocate(stateName: String?) {
        val doc = canvas.svgDocument ?: return
        // Restore previous shape's fill — only when it still belongs to the
        // live document; a shape from a swapped-out document must not be
        // patched (and its live counterpart was reset by the swap anyway).
        locatedShape?.let { prev ->
            if (prev.ownerDocument === doc) {
                if (locatedOriginalFill != null) prev.setAttribute("fill", locatedOriginalFill)
                else prev.removeAttribute("fill")
            }
        }
        locatedShape = null
        locatedOriginalFill = null
        if (stateName == null) return

        val anchor = findAnchor(doc.documentElement, StateUri.encode(stateName)) ?: return
        val shape = findFirstShape(anchor) ?: return
        locatedOriginalFill = if (shape.hasAttribute("fill")) shape.getAttribute("fill") else null
        shape.setAttribute("fill", WorkflowVizSettings.getInstance().state.locateColor)
        locatedShape = shape
        bringIntoView(shape)
    }

    /**
     * If [shape]'s on-screen rectangle isn't fully visible in the canvas viewport,
     * translate the rendering transform (preserving zoom) so the shape ends up
     * centered. Computed on the Batik update thread, applied on the EDT.
     */
    private fun bringIntoView(shape: Element) {
        val ctx = canvas.updateManager?.bridgeContext ?: return
        val gn = ctx.getGraphicsNode(shape) ?: return
        val localBounds = gn.bounds ?: return
        val globalTx = gn.globalTransform ?: AffineTransform()
        val userBounds = globalTx.createTransformedShape(localBounds).bounds2D
        SwingUtilities.invokeLater {
            val w = canvas.width
            val h = canvas.height
            if (w <= 0 || h <= 0) return@invokeLater
            val rt = canvas.renderingTransform ?: AffineTransform()
            // Below 100% (user zoomed out past the Batik auto-fit): snap back to
            // fit so the whole diagram is visible with the located node coloured
            // in. Reset = identity rendering transform — Batik then re-applies
            // initialTransform (document fit) on its own.
            if (rt.scaleX < 1.0) {
                canvas.setRenderingTransform(AffineTransform(), true)
                return@invokeLater
            }
            // 100% or zoomed in: keep zoom, pan only if the shape isn't already
            // inside the viewport (with a small margin so it isn't flush against
            // the edge).
            val effective = AffineTransform(rt)
            canvas.initialTransform?.let { effective.concatenate(it) }
            val screenBounds = effective.createTransformedShape(userBounds).bounds2D
            val margin = 16.0
            val visible = Rectangle2D.Double(margin, margin, w - 2 * margin, h - 2 * margin)
            if (visible.contains(screenBounds)) return@invokeLater
            val dx = w / 2.0 - screenBounds.centerX
            val dy = h / 2.0 - screenBounds.centerY
            val t = AffineTransform.getTranslateInstance(dx, dy)
            t.concatenate(rt)
            canvas.setRenderingTransform(t, true)
        }
    }

    /** Depth-first search for the first `<a>` whose href matches [uri]. */
    private fun findAnchor(root: Element, uri: String): Element? {
        if (root.localName == "a") {
            val href = root.getAttribute("xlink:href").ifEmpty {
                root.getAttributeNS("http://www.w3.org/1999/xlink", "href").ifEmpty {
                    root.getAttribute("href")
                }
            }
            if (href == uri) return root
        }
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            findAnchor(child, uri)?.let { return it }
        }
        return null
    }

    /** First descendant shape (`rect`, `polygon`, `ellipse`, `path`) under [parent]. */
    private fun findFirstShape(parent: Element): Element? {
        for (tag in listOf("rect", "polygon", "ellipse", "path")) {
            val nodes = parent.getElementsByTagName(tag)
            if (nodes.length > 0) return nodes.item(0) as? Element
        }
        return null
    }

    /**
     * Stop Batik's update manager and release the GVT tree. Without this,
     * closing the tool window leaks the canvas's RunnableQueue thread.
     */
    fun dispose() {
        canvas.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(DiagramPanel::class.java)
        /**
         * Base document URI for the parsed SVG (not a state anchor). Contains
         * `/`, which [StateUri.encode] never emits — no state name can collide.
         */
        private const val DOC_URI = StateUri.SCHEME + "internal/diagram"

        /**
         * Parse a rendered SVG string into the document [setSvg] consumes.
         * Thread-safe and CPU-bound — call it on a background thread so the
         * O(SVG-size) XML parse doesn't jank the EDT on every re-render.
         */
        fun parseSvg(svg: String): SVGDocument {
            val factory = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
            val doc = factory.createSVGDocument(DOC_URI, StringReader(svg))
            // PlantUML emits preserveAspectRatio="none" on the root <svg>, which
            // makes Batik's fit-to-viewport scale X and Y independently → the
            // diagram stretches whenever the canvas aspect ratio differs from the
            // diagram's. Force uniform scaling + centering. Batik re-reads this
            // attribute on every resize (componentResized → updateRenderingTransform
            // → ViewBox.getViewTransform), so the fix holds across user resizes
            // too, not just the initial fit.
            doc.documentElement?.setAttribute("preserveAspectRatio", "xMidYMid meet")
            return doc
        }
        /** Zoom multiplier per wheel unit (⌘/Ctrl + scroll). */
        private const val ZOOM_STEP = 1.15
        /** Zoom multiplier per toolbar/keyboard step — coarser, fewer clicks. */
        private const val ZOOM_BUTTON_STEP = 1.25
        /** Screen pixels panned per wheel unit (plain scroll). */
        private const val PAN_STEP = 32.0
        /** Rendering-transform scale bounds — Batik rasterizes at the target scale. */
        private const val MIN_SCALE = 0.05
        private const val MAX_SCALE = 40.0
    }
}
