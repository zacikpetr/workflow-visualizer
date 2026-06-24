package tech.zacik.workflowviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the .sw.json → PlantUML generator (no IDE fixture needed). */
class SwJsonToPumlTest {

    private fun puml(json: String): String =
        SwJsonToPuml.toPuml(WorkflowJson.parse(json) ?: error("fixture must parse"))

    @Test
    fun `colliding sanitized names get distinct aliases`() {
        val out = puml(
            """{"start":"A B","states":[
                {"name":"A B","type":"operation","transition":"A_B"},
                {"name":"A_B","type":"operation","end":true}]}""",
        )
        assertTrue(out.contains("state \"A B\" as A_B "))
        assertTrue(out.contains("state \"A_B\" as A_B_2 "))
        assertTrue("edge must point at the disambiguated alias", out.contains("A_B --> A_B_2"))
    }

    @Test
    fun `object form start and end produce edges`() {
        val out = puml(
            """{"start":{"stateName":"S"},"states":[
                {"name":"S","type":"operation","end":{"terminate":true}}]}""",
        )
        assertTrue(out.contains("[*] --> S"))
        assertTrue(out.contains("S --> [*]"))
    }

    @Test
    fun `condition-level end draws a labelled terminal edge`() {
        val out = puml(
            """{"start":"SW","states":[
                {"name":"SW","type":"switch",
                 "dataConditions":[{"name":"done","condition":".x","end":true}],
                 "defaultCondition":{"end":{"terminate":true}}}]}""",
        )
        assertTrue(out.contains("SW --> [*] : done"))
        assertTrue(out.contains("SW --> [*] : default"))
    }

    @Test
    fun `compensatedBy target is reachable and linked with a dashed edge`() {
        val out = puml(
            """{"start":"A","states":[
                {"name":"A","type":"operation","transition":"B","compensatedBy":"C"},
                {"name":"B","type":"operation","end":true},
                {"name":"C","type":"operation","usedForCompensation":true,"end":true}]}""",
        )
        assertTrue(out.contains("A -[dashed]-> C : compensate"))
        assertFalse("compensation handler must not be struck through", out.contains("<s>C</s>"))
        assertFalse("compensation handler must not be dimmed", out.lines().any { it.startsWith("state \"C\"") && it.contains("ececec") })
    }

    @Test
    fun `quotes and spaces in names are escaped in label and URI`() {
        val out = puml(
            """{"start":"Undo \"X\"","states":[
                {"name":"Undo \"X\"","type":"operation","end":true}]}""",
        )
        assertTrue(out.contains("state \"Undo 'X'\""))
        assertTrue(out.contains("[[swjson://Undo%20%22X%22]]"))
    }

    @Test
    fun `state uri roundtrip`() {
        for (name in listOf("plain", "with space", "Undo \"X\"", "a+b/c", "háčky a čárky")) {
            assertEquals(name, StateUri.decode(StateUri.encode(name)))
        }
    }

    @Test
    fun `toPumlResult exposes the state name to alias map matching the edges`() {
        val wf = WorkflowJson.parse(
            """{"start":"A B","states":[
                {"name":"A B","type":"operation","transition":"A_B"},
                {"name":"A_B","type":"operation","end":true}]}""",
        ) ?: error("fixture must parse")
        val result = SwJsonToPuml.toPumlResult(wf)
        // Map keys are the state names; values are the aliases used in the edges.
        assertEquals("A_B", result.nameToAlias["A B"])
        assertEquals("A_B_2", result.nameToAlias["A_B"])
        assertTrue(result.puml.contains("A_B --> A_B_2"))
    }

    /**
     * Guards the contract the diagram's focus mode depends on: PlantUML must wrap
     * each edge in `<g class="link" data-entity-1="<sourceAlias>" …>`. If a future
     * bundled PlantUML drops these attributes, the path highlight silently stops
     * finding outgoing edges — this fails loudly instead.
     */
    @Test
    fun `rendered SVG tags each edge with its source alias`() {
        val wf = WorkflowJson.parse(
            """{"start":"A","states":[
                {"name":"A","type":"switch",
                 "dataConditions":[{"name":"ok","condition":".x","transition":"B"}],
                 "onErrors":[{"transition":"C"}]},
                {"name":"B","type":"operation","end":true},
                {"name":"C","type":"operation","end":true}]}""",
        ) ?: error("fixture must parse")
        val result = SwJsonToPuml.toPumlResult(wf)
        val svg = PlantUmlRenderer.toSvg(result.puml)
        val aliasA = result.nameToAlias.getValue("A")
        assertTrue("edges must render as link groups", svg.contains("class=\"link\""))
        assertTrue(
            "A's outgoing edges must carry data-entity-1=\"$aliasA\"",
            svg.contains("data-entity-1=\"$aliasA\""),
        )
        // A's two outgoing edges (the switch 'ok' branch + the error edge) target B and C.
        assertTrue(svg.contains("data-entity-2=\"${result.nameToAlias.getValue("B")}\""))
        assertTrue(svg.contains("data-entity-2=\"${result.nameToAlias.getValue("C")}\""))
    }
}
