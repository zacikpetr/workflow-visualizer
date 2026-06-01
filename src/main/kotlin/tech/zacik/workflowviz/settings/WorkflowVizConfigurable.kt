package tech.zacik.workflowviz.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Workflow Visualizer. Built with the Kotlin UI DSL so the
 * form binds directly to [WorkflowVizSettings.State] fields and IntelliJ
 * tracks modified/reset/apply for us.
 *
 * Validation lives next to each control — keep the rules narrow (regex on
 * hex, int range on truncation) so the user sees the problem inline.
 */
class WorkflowVizConfigurable : BoundConfigurable("Workflow Visualizer") {

    override fun createPanel(): DialogPanel {
        val state = WorkflowVizSettings.getInstance().state
        return panel {
            group("Editor") {
                row {
                    checkBox("Semantic coloring of references and state names")
                        .comment("Tints state / function / event / error references and state-name definitions in .sw.json")
                        .bindSelected(state::semanticColoring)
                }
            }
            group("Diagram") {
                row("Located state color:") {
                    textField()
                        .bindText(state::locateColor)
                        .columns(8)
                        .comment("Hex color like <code>#ffd54a</code>. Applied as fill on the selected state in the SVG.")
                        .validationOnInput { tf ->
                            if (HEX_RE.matches(tf.text)) null
                            else error("Expected #rrggbb or #rgb")
                        }
                }
                row("Edge label max characters:") {
                    intTextField(0..200)
                        .bindIntText(state::labelMaxChars)
                        .columns(4)
                        .comment("Long condition / event names are truncated to this length in the diagram. <code>0</code> disables truncation.")
                }
                row {
                    checkBox("Color onErrors edges red")
                        .comment("Visually separate error transitions from happy-path edges.")
                        .bindSelected(state::colorErrorEdges)
                }
                row {
                    checkBox("Dim unreachable states")
                        .comment("Gray out states (and their outgoing edges) that BFS from <code>start</code> can't reach.")
                        .bindSelected(state::dimUnreachable)
                }
            }
            group("Mutation badges") {
                row {
                    checkBox("Show fields each state mutates")
                        .comment("Reads <code>expression</code>-typed functions and annotates each state node with the runtime fields its actions set (e.g. <code>state: \"ACTIVE\"</code>).")
                        .bindSelected(state::mutationBadgesEnabled)
                }
                row("Fields:") {
                    textField()
                        .bindText(state::mutationBadgeFields)
                        .columns(40)
                        .comment("Comma-separated runtime-state field names. Defaults cover the most common ones: <code>state, phase, continuationPoint, stateDetail</code>.")
                }
            }
        }
    }

    /**
     * After the bound state is committed, broadcast so open diagram tool windows
     * can refresh — otherwise toggles like "show badges" only take effect on
     * the next document edit / caret move.
     */
    override fun apply() {
        super.apply()
        ApplicationManager.getApplication().messageBus
            .syncPublisher(WorkflowVizSettingsListener.TOPIC)
            .settingsChanged()
    }

    companion object {
        private val HEX_RE = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
    }
}
