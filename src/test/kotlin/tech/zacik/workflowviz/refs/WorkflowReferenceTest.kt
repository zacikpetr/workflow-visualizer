package tech.zacik.workflowviz.refs

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.pom.PomTargetPsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Reference resolution + rename over in-memory `.sw.json` fixtures. */
class WorkflowReferenceTest : BasePlatformTestCase() {

    fun testTransitionResolvesToDefinitionTarget() {
        myFixture.configureByText(
            "test.sw.json",
            """{"start":"First","states":[
                {"name":"First","type":"operation","transition":"Sec<caret>ond"},
                {"name":"Second","type":"operation","end":true}]}""",
        )
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("transition value must carry a reference", ref)
        val resolved = ref!!.resolve()
        assertTrue("resolves to the POM definition target", resolved is PomTargetPsiElement)
        assertEquals("Second", ((resolved as PomTargetPsiElement).target as WorkflowDefinitionTarget).name)
    }

    fun testRenameFromUsageRenamesDefinitionAndUsages() {
        myFixture.configureByText(
            "test.sw.json",
            """{"start":"First","states":[
                {"name":"First","type":"operation","transition":"Sec<caret>ond"},
                {"name":"Second","type":"operation","end":true}]}""",
        )
        myFixture.renameElementAtCaret("Renamed")
        // The JSON manipulator re-emits renamed properties with the code-style
        // space after the colon — hence the asymmetric formatting here.
        myFixture.checkResult(
            """{"start":"First","states":[
                {"name":"First","type":"operation","transition": "Renamed"},
                {"name": "Renamed","type":"operation","end":true}]}""",
        )
    }

    fun testRenameFromDefinitionRenamesUsages() {
        myFixture.configureByText(
            "test.sw.json",
            """{"start":"First","states":[
                {"name":"First","type":"operation","transition":"Second"},
                {"name":"Sec<caret>ond","type":"operation","end":true}]}""",
        )
        myFixture.renameElementAtCaret("Renamed")
        myFixture.checkResult(
            """{"start":"First","states":[
                {"name":"First","type":"operation","transition": "Renamed"},
                {"name": "Renamed","type":"operation","end":true}]}""",
        )
    }

    fun testStartValueInForeignJsonIsNotAWorkflowReference() {
        myFixture.configureByText(
            "package.json",
            """{"scripts":{"start":"node ser<caret>ver.js"},"name":"x"}""",
        )
        val literal = myFixture.file.findElementAt(myFixture.caretOffset)!!.parent as JsonStringLiteral
        // `start` outside the workflow root (here: nested under `scripts`)
        // must not classify as a state reference.
        assertFalse(literal.references.any { it is WorkflowReference })
    }
}
