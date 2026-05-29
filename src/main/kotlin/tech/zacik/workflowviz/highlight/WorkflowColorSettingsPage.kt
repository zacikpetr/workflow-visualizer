package tech.zacik.workflowviz.highlight

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.json.JsonLanguage
import javax.swing.Icon

/**
 * Adds a **Workflow Visualizer** entry under Settings → Editor → Color Scheme so
 * users can rebind any of the semantic colour keys. The demo text on the right
 * is a minimal `.sw.json` skeleton with `<key>name</key>` markers — IntelliJ
 * paints those positions using the matching attribute key from
 * [getAdditionalHighlightingTagToDescriptorMap].
 */
class WorkflowColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "Workflow Visualizer"

    override fun getIcon(): Icon = AllIcons.FileTypes.Json

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(JsonLanguage.INSTANCE, null, VirtualFile.EMPTY_ARRAY.firstOrNull())
            ?: SyntaxHighlighterFactory.getSyntaxHighlighter(JsonLanguage.INSTANCE, null, null)!!

    override fun getDemoText(): String = """
        {
          "id": "sampleWorkflow",
          "specVersion": "0.8",
          "start": "<stateStart>chooseBranch</stateStart>",
          "errors": [
            { "name": "<defError>defaultException</defError>", "code": "java.lang.Exception" }
          ],
          "functions": [
            { "name": "<defFunction>processOrder</defFunction>", "operation": "functions.yaml#processOrder" }
          ],
          "events": [
            { "name": "<defEvent>orderUpdated</defEvent>", "source": "loan.events", "type": "orderUpdated" }
          ],
          "states": [
            {
              "name": "<nameSwitch>chooseBranch</nameSwitch>",
              "type": "switch",
              "dataConditions": [
                { "condition": "${'$'}{ .x > 0 }", "transition": "<refState>doWork</refState>" }
              ],
              "defaultCondition": { "transition": "<refState>finish</refState>" }
            },
            {
              "name": "<nameOperation>doWork</nameOperation>",
              "type": "operation",
              "actions": [
                { "name": "call", "functionRef": { "refName": "<refFunction>processOrder</refFunction>" } }
              ],
              "transition": "<refState>finish</refState>"
            },
            {
              "name": "<nameEvent>waitForUpdate</nameEvent>",
              "type": "event",
              "onEvents": [ { "eventRefs": [ "<refEvent>orderUpdated</refEvent>" ] } ],
              "onErrors": [ { "errorRef": "<refError>defaultException</refError>", "transition": "<refState>finish</refState>" } ],
              "transition": "<refState>finish</refState>"
            },
            {
              "name": "<nameForeach>processItems</nameForeach>",
              "type": "foreach",
              "inputCollection": "${'$'}{ .items }",
              "transition": "<refState>finish</refState>"
            },
            {
              "name": "<nameEnd>finish</nameEnd>",
              "type": "operation",
              "end": true
            }
          ]
        }
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("References//State reference", WorkflowColors.STATE_REFERENCE),
            AttributesDescriptor("References//Function reference", WorkflowColors.FUNCTION_REFERENCE),
            AttributesDescriptor("References//Event reference", WorkflowColors.EVENT_REFERENCE),
            AttributesDescriptor("References//Error reference", WorkflowColors.ERROR_REFERENCE),
            AttributesDescriptor("Definition name//Function", WorkflowColors.FUNCTION_NAME),
            AttributesDescriptor("Definition name//Event", WorkflowColors.EVENT_NAME),
            AttributesDescriptor("Definition name//Error", WorkflowColors.ERROR_NAME),
            AttributesDescriptor("State name//Operation", WorkflowColors.STATE_NAME_OPERATION),
            AttributesDescriptor("State name//Switch", WorkflowColors.STATE_NAME_SWITCH),
            AttributesDescriptor("State name//Event", WorkflowColors.STATE_NAME_EVENT),
            AttributesDescriptor("State name//Foreach", WorkflowColors.STATE_NAME_FOREACH),
            AttributesDescriptor("State name//Start", WorkflowColors.STATE_NAME_START),
            AttributesDescriptor("State name//End", WorkflowColors.STATE_NAME_END),
            AttributesDescriptor("State name//Other", WorkflowColors.STATE_NAME),
        )
        private val TAGS = mapOf(
            "refState" to WorkflowColors.STATE_REFERENCE,
            "refFunction" to WorkflowColors.FUNCTION_REFERENCE,
            "refEvent" to WorkflowColors.EVENT_REFERENCE,
            "refError" to WorkflowColors.ERROR_REFERENCE,
            "defFunction" to WorkflowColors.FUNCTION_NAME,
            "defEvent" to WorkflowColors.EVENT_NAME,
            "defError" to WorkflowColors.ERROR_NAME,
            "nameOperation" to WorkflowColors.STATE_NAME_OPERATION,
            "nameSwitch" to WorkflowColors.STATE_NAME_SWITCH,
            "nameEvent" to WorkflowColors.STATE_NAME_EVENT,
            "nameForeach" to WorkflowColors.STATE_NAME_FOREACH,
            "stateStart" to WorkflowColors.STATE_NAME_START,
            "nameEnd" to WorkflowColors.STATE_NAME_END,
        )
    }
}
