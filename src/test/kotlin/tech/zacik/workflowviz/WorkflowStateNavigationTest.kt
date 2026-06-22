package tech.zacik.workflowviz

import com.intellij.json.psi.JsonFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Structural caret ↔ state mapping over in-memory `.sw.json` fixtures. */
class WorkflowStateNavigationTest : BasePlatformTestCase() {

    private fun configure(text: String): JsonFile {
        myFixture.configureByText("test.sw.json", text)
        return myFixture.file as JsonFile
    }

    // ── caret → state (enclosingStateName) ──────────────────────────────────

    fun testCaretAnywhereInStateObjectResolvesToThatState() {
        val file = configure(
            """{"states":[
                {"name":"A","type":"oper<caret>ation","transition":"B"},
                {"name":"B","end":true}]}""",
        )
        assertEquals("A", WorkflowStateNavigation.enclosingStateName(file, myFixture.caretOffset))
    }

    fun testCaretBeforeNameQuoteStillResolvesToTheSameState() {
        val file = configure(
            """{"states":[
                {"name":"A","end":true},
                {"name": <caret>"B","end":true}]}""",
        )
        // Caret before the opening quote of "B" — the old text-search fell into A.
        assertEquals("B", WorkflowStateNavigation.enclosingStateName(file, myFixture.caretOffset))
    }

    fun testCaretOnNestedFunctionRefResolvesToEnclosingState() {
        val file = configure(
            """{"functions":[{"name":"reg","operation":"f#reg"}],
                "states":[
                  {"name":"S","actions":[{"functionRef":{"refName":"r<caret>eg"}}],"end":true}]}""",
        )
        assertEquals("S", WorkflowStateNavigation.enclosingStateName(file, myFixture.caretOffset))
    }

    fun testCaretInFunctionDefinitionWithCollidingNameResolvesToNull() {
        val file = configure(
            """{"functions":[{"name":"r<caret>eg","operation":"f#reg"}],
                "states":[{"name":"reg","end":true}]}""",
        )
        // "reg" names both a function and a state; the caret is in the function.
        assertNull(WorkflowStateNavigation.enclosingStateName(file, myFixture.caretOffset))
    }

    fun testCaretInTopLevelMetadataResolvesToNull() {
        val file = configure(
            """{"na<caret>me":"Workflow","states":[{"name":"A","end":true}]}""",
        )
        assertNull(WorkflowStateNavigation.enclosingStateName(file, myFixture.caretOffset))
    }

    // ── state → caret (stateNameOffset) ─────────────────────────────────────

    fun testStateNameOffsetSkipsCollidingFunctionDefinition() {
        val file = configure(
            """{"functions":[{"name":"reg","operation":"f#reg"}],
                "states":[{"name":"reg","type":"operation","end":true}]}""",
        )
        val offset = WorkflowStateNavigation.stateNameOffset(file, "reg")
        assertNotNull("a state named reg exists", offset)
        // Round-trips to the state, not the earlier same-named function entry.
        assertEquals("reg", WorkflowStateNavigation.enclosingStateName(file, offset!!))
        assertTrue("offset is past the functions section", offset > file.text.indexOf("\"operation\""))
    }

    fun testStateNameOffsetIsNullForUnknownState() {
        val file = configure("""{"states":[{"name":"A","end":true}]}""")
        assertNull(WorkflowStateNavigation.stateNameOffset(file, "Nope"))
    }
}
