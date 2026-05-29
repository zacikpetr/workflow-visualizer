package tech.zacik.workflowviz.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for the Workflow Visualizer plugin. Application scope
 * (not project) keeps the user's preferences across projects — no project
 * touches our config files. Stored as `workflowViz.xml` under IDE config dir.
 *
 * Defaults preserve the behaviour from before settings existed; adding a new
 * setting should not change anything for existing users until they opt in.
 */
@State(
    name = "WorkflowVizSettings",
    storages = [Storage("workflowViz.xml")],
)
class WorkflowVizSettings : PersistentStateComponent<WorkflowVizSettings.State> {

    /**
     * Field types kept primitive (`var` + default) so they round-trip through
     * the IntelliJ XML serializer without custom converters.
     *
     * `locateColor` is a 7-char `#rrggbb` string — easier to validate / paint
     * than `java.awt.Color`, and matches the SVG fill attribute we set directly.
     */
    data class State(
        /** Apply per-token colour to references and state names in the JSON editor. */
        var semanticColoring: Boolean = true,
        /** Fill colour for the located state shape in the SVG (hex, `#rrggbb`). */
        var locateColor: String = "#ffd54a",
        /** Max characters on edge labels in the diagram; 0 disables truncation. */
        var labelMaxChars: Int = 25,
        /** Render `onErrors` edges in red (vs. theme default) for at-a-glance triage. */
        var colorErrorEdges: Boolean = true,
        /** Render states unreachable from `start` (and their outgoing edges) dimmed in the diagram. */
        var dimUnreachable: Boolean = true,
        /** Annotate each state node with the runtime fields its actions mutate. */
        var mutationBadgesEnabled: Boolean = false,
        /**
         * Comma-separated runtime fields to surface as mutation badges. Defaults
         * cover the most common workflow-runtime fields; projects with custom
         * field names can edit this list in the Settings page.
         * Stored as a single string to keep the XML serializer happy with primitives.
         */
        var mutationBadgeFields: String = "state,phase,continuationPoint,stateDetail",
    )

    /** Parse [State.mutationBadgeFields] into a trimmed, lowercase-aware set. */
    fun mutationFieldSet(): Set<String> = state.mutationBadgeFields
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        // Use XmlSerializerUtil.copyBean so unset fields from older configs keep
        // their default values instead of being null.
        XmlSerializerUtil.copyBean(loaded, state)
    }

    companion object {
        fun getInstance(): WorkflowVizSettings = service()
    }
}
