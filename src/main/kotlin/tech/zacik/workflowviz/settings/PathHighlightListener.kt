package tech.zacik.workflowviz.settings

import com.intellij.util.messages.Topic

/**
 * Broadcast when the focus-mode toggle ([WorkflowVizSettings.State.highlightSelectedPath])
 * flips, so every open diagram converges on the single global setting.
 *
 * Distinct from [WorkflowVizSettingsListener] on purpose: that topic drives a
 * full PlantUML re-render, which is the wrong, heavy response to a toggle that
 * only needs a cheap in-place SVG DOM patch. Each controller reacts by calling
 * `panel.setPathHighlight(...)` — no re-render.
 *
 * Application scope so a toggle in one project reaches every project's controller.
 */
fun interface PathHighlightListener {
    fun pathHighlightChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<PathHighlightListener> = Topic.create(
            "WorkflowViz path highlight toggled",
            PathHighlightListener::class.java,
        )
    }
}
