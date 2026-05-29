package tech.zacik.workflowviz.settings

import com.intellij.util.messages.Topic

/**
 * Broadcast on Apply in the Workflow Visualizer Settings panel so open tool
 * windows can re-render with the new config — otherwise a toggle (e.g. enable
 * mutation badges) only takes effect after the next document edit / caret move.
 *
 * Application scope: settings are application-level and so is the listener,
 * which lets a single Configurable.apply() reach every project's controller.
 */
fun interface WorkflowVizSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<WorkflowVizSettingsListener> = Topic.create(
            "WorkflowVizSettings changed",
            WorkflowVizSettingsListener::class.java,
        )
    }
}
