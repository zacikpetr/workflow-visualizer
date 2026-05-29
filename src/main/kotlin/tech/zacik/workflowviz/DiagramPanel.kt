package tech.zacik.workflowviz

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.UpdateManager
import org.apache.batik.bridge.UpdateManagerAdapter
import org.apache.batik.bridge.UpdateManagerEvent
import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.swing.svg.JSVGComponent
import org.apache.batik.swing.svg.SVGUserAgentAdapter
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.Element
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
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Batik-based SVG viewer for the rendered PlantUML diagram.
 *
 * Batik's built-in interactors have unintuitive triggers, so we drive the
 * rendering transform ourselves: **mouse wheel = zoom at cursor**, **left-drag =
 * pan**. Batik re-renders the vector on zoom → crisp at any scale. Clicking a
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
            if (uri.startsWith(SCHEME)) onStateClicked(uri.removePrefix(SCHEME))
        }
    }

    private var dragStart: Point? = null

    // ── locate (in-place SVG DOM patch — avoids re-rendering the whole diagram) ──
    /** Name of the currently located state, re-applied after each setSvg. */
    private var currentLocate: String? = null
    /** The shape element we re-coloured, plus its original fill (or null if none). */
    private var locatedShape: Element? = null
    private var locatedOriginalFill: String? = null

    init {
        add(canvas, BorderLayout.CENTER)
        wireZoomAndPan()
    }

    private fun wireZoomAndPan() {
        canvas.addMouseWheelListener { e: MouseWheelEvent ->
            val factor = if (e.wheelRotation < 0) 1.15 else 1.0 / 1.15
            zoomAt(factor, e.x, e.y)
        }
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) dragStart = e.point
            }
            override fun mouseReleased(e: MouseEvent) { dragStart = null }
        })
        canvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                val cur = canvas.renderingTransform ?: return
                val pan = AffineTransform.getTranslateInstance(
                    (e.x - start.x).toDouble(), (e.y - start.y).toDouble(),
                )
                pan.concatenate(cur)
                canvas.setRenderingTransform(pan, true)
                dragStart = e.point
            }
        })
    }

    /** Zoom by [factor] keeping the point (x,y) under the cursor fixed. */
    private fun zoomAt(factor: Double, x: Int, y: Int) {
        val cur = canvas.renderingTransform ?: return
        val at = AffineTransform()
        at.translate(x.toDouble(), y.toDouble())
        at.scale(factor, factor)
        at.translate(-x.toDouble(), -y.toDouble())
        at.concatenate(cur)
        canvas.setRenderingTransform(at, true)
    }

    /**
     * Replace the displayed diagram. Call on the EDT.
     *
     * Preserves the current zoom/pan across the swap **only when zoomed in
     * (scale ≥ 1.0)** — i.e. you're inspecting detail and don't want re-renders
     * (locate, edit) yanking the view back to fit. When zoomed out (scale <
     * 1.0, the typical "overview" state) we let Batik auto-fit the new SVG
     * so a freshly grown diagram stays fully visible. First load = always fit.
     */
    fun setSvg(svg: String) {
        val previousRT = canvas.renderingTransform
        val preserve = previousRT != null && previousRT.scaleX >= 1.0

        val factory = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        val doc = factory.createSVGDocument(SCHEME + "diagram", StringReader(svg))
        // Tracking state belongs to the old document; clear it before swap.
        locatedShape = null
        locatedOriginalFill = null
        if (preserve) {
            // Batik internally calls setRenderingTransform(initialTransform)
            // *between* gvtBuildCompleted and managerStarted (after recomputing
            // the document fit), so restoring earlier gets overwritten one tick
            // later. managerStarted fires after that recompute — the right
            // hook. Fires on Batik's RunnableQueue thread → dispatch to EDT.
            canvas.addUpdateManagerListener(object : UpdateManagerAdapter() {
                override fun managerStarted(e: UpdateManagerEvent) {
                    canvas.removeUpdateManagerListener(this)
                    SwingUtilities.invokeLater {
                        canvas.setRenderingTransform(previousRT, true)
                    }
                }
            })
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
        // Restore previous shape's fill.
        locatedShape?.let { prev ->
            if (locatedOriginalFill != null) prev.setAttribute("fill", locatedOriginalFill)
            else prev.removeAttribute("fill")
        }
        locatedShape = null
        locatedOriginalFill = null
        if (stateName == null) return

        val anchor = findAnchor(doc.documentElement, SCHEME + stateName) ?: return
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

    companion object {
        const val SCHEME = "swjson://"
    }
}
