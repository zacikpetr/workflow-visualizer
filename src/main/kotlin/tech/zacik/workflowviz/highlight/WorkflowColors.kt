package tech.zacik.workflowviz.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Semantic highlight keys for `.sw.json`. Each key has a *fallback* mapping to a
 * built-in language colour so users see a sensible default in any colour scheme
 * — they can then rebind specific keys under Settings → Editor → Color Scheme
 * → Workflow Visualizer.
 *
 * Two layers:
 *
 *  - **References** — the string *value* under `transition`, `functionRef`,
 *    `eventRef`, `errorRef`, … Tells you "this is a link, Ctrl+B works".
 *  - **State names** — the `"name"` *value* of each entry in `states[]`,
 *    tinted by `type` (operation / switch / event / foreach) plus the
 *    distinguished start- and end-state names. Mirrors the diagram colour
 *    scheme so the editor and the panel reinforce each other visually.
 */
object WorkflowColors {

    // ── References ─────────────────────────────────────────────────────────
    val STATE_REFERENCE = key("WORKFLOW_STATE_REFERENCE", DefaultLanguageHighlighterColors.CONSTANT)
    val FUNCTION_REFERENCE = key("WORKFLOW_FUNCTION_REFERENCE", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    val EVENT_REFERENCE = key("WORKFLOW_EVENT_REFERENCE", DefaultLanguageHighlighterColors.METADATA)
    val ERROR_REFERENCE = key("WORKFLOW_ERROR_REFERENCE", DefaultLanguageHighlighterColors.LABEL)

    // ── Other definitions ──────────────────────────────────────────────────
    /** The `name` value of an entry in top-level `functions[]`. */
    val FUNCTION_NAME = key("WORKFLOW_FUNCTION_NAME", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    /** The `name` value of an entry in top-level `events[]`. */
    val EVENT_NAME = key("WORKFLOW_EVENT_NAME", DefaultLanguageHighlighterColors.METADATA)
    /** The `name` value of an entry in top-level `errors[]`. */
    val ERROR_NAME = key("WORKFLOW_ERROR_NAME", DefaultLanguageHighlighterColors.LABEL)

    // ── State name definitions, by type ────────────────────────────────────
    val STATE_NAME_OPERATION = key("WORKFLOW_STATE_NAME_OPERATION", DefaultLanguageHighlighterColors.CLASS_NAME)
    val STATE_NAME_SWITCH = key("WORKFLOW_STATE_NAME_SWITCH", DefaultLanguageHighlighterColors.KEYWORD)
    val STATE_NAME_EVENT = key("WORKFLOW_STATE_NAME_EVENT", DefaultLanguageHighlighterColors.INTERFACE_NAME)
    val STATE_NAME_FOREACH = key("WORKFLOW_STATE_NAME_FOREACH", DefaultLanguageHighlighterColors.STATIC_FIELD)
    /** Whichever state is referenced from top-level `start`. */
    val STATE_NAME_START = key("WORKFLOW_STATE_NAME_START", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    /** Any state with `end: true`. */
    val STATE_NAME_END = key("WORKFLOW_STATE_NAME_END", DefaultLanguageHighlighterColors.MARKUP_TAG)
    /** Fallback when the type isn't one of the recognised values. */
    val STATE_NAME = key("WORKFLOW_STATE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)

    private fun key(externalName: String, fallback: TextAttributesKey): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(externalName, fallback)
}
