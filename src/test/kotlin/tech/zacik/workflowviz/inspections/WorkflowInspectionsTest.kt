package tech.zacik.workflowviz.inspections

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Inspection behaviour over in-memory `.sw.json` fixtures. Assertions match on
 * highlight descriptions instead of `<error>` markup — file-level problems
 * anchor on the root, which inline markup pins down too brittlely.
 */
class WorkflowInspectionsTest : BasePlatformTestCase() {

    private fun highlight(json: String): List<HighlightInfo> {
        myFixture.configureByText("test.sw.json", json)
        myFixture.enableInspections(
            WorkflowUnresolvedReferenceInspection(),
            UnreachableStatesInspection(),
            UnusedStatesInspection(),
            TerminalStatesInspection(),
            StartStateInspection(),
        )
        return myFixture.doHighlighting()
    }

    private fun List<HighlightInfo>.descriptions(): List<String> = mapNotNull { it.description }

    fun testMissingTransitionTargetIsReported() {
        val infos = highlight(
            """{"start":"A","states":[
                {"name":"A","type":"operation","transition":"Ghost","end":true}]}""",
        )
        assertTrue(infos.descriptions().any { it.contains("Missing state definition: 'Ghost'") })
    }

    fun testCompensatedByTargetIsNeitherUnreachableNorUnused() {
        val infos = highlight(
            """{"start":"A","states":[
                {"name":"A","type":"operation","transition":"B","compensatedBy":"C"},
                {"name":"B","type":"operation","end":true},
                {"name":"C","type":"operation","usedForCompensation":true,"end":true}]}""",
        )
        assertFalse(infos.descriptions().any { it.contains("Unreachable state 'C'") })
        assertFalse(infos.descriptions().any { it.contains("Unused state 'C'") })
        assertFalse(infos.descriptions().any { it.contains("Missing state definition: 'C'") })
    }

    fun testObjectFormStartIsUnderstood() {
        val infos = highlight(
            """{"start":{"stateName":"A","schedule":{"start":"2026-01-01T00:00:00Z"}},"states":[
                {"name":"A","type":"operation","end":true}]}""",
        )
        assertFalse(infos.descriptions().any { it.contains("No 'start' state defined") })
        assertFalse(infos.descriptions().any { it.contains("Unreachable state 'A'") })
        assertFalse(infos.descriptions().any { it.contains("Unused state 'A'") })
        // The nested schedule.start date must not be treated as a state reference.
        assertFalse(infos.descriptions().any { it.contains("Missing state definition: '2026-01-01") })
    }

    fun testConditionLevelEndCountsAsTerminal() {
        val infos = highlight(
            """{"start":"SW","states":[
                {"name":"SW","type":"switch",
                 "dataConditions":[{"name":"done","condition":".x","end":true}],
                 "defaultCondition":{"transition":"SW"}}]}""",
        )
        assertFalse(infos.descriptions().any { it.contains("no terminal state") })
    }

    fun testWorkflowWithoutAnyEndIsFlagged() {
        val infos = highlight(
            """{"start":"A","states":[
                {"name":"A","type":"operation","transition":"B"},
                {"name":"B","type":"operation","transition":"A"}]}""",
        )
        assertTrue(infos.descriptions().any { it.contains("no terminal state") })
    }

    fun testUnreachableStateIsFlagged() {
        val infos = highlight(
            """{"start":"A","states":[
                {"name":"A","type":"operation","end":true},
                {"name":"Orphan","type":"operation","end":true}]}""",
        )
        assertTrue(infos.descriptions().any { it.contains("Unreachable state 'Orphan'") })
    }
}
