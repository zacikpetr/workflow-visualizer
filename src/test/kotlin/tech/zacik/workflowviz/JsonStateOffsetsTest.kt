package tech.zacik.workflowviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonStateOffsetsTest {

    private val text = """
        {"start":"A",
         "states":[
           {"name":"A","transition":"B"},
           {"name":"B","end":true}],
         "functions":[{"name":"f1"}]}
    """.trimIndent()

    @Test
    fun `nameOffsets collects only state names in order`() {
        val offsets = JsonStateOffsets.nameOffsets(text, listOf("A", "B"))
        assertEquals(listOf("A", "B"), offsets.map { it.second })
        assertEquals(offsets.map { it.first }.sorted(), offsets.map { it.first })
        // f1 is a function, not a state — must not appear.
        assertEquals(2, offsets.size)
    }

    @Test
    fun `enclosingState picks the last declaration at or before the caret`() {
        val offsets = JsonStateOffsets.nameOffsets(text, listOf("A", "B"))
        val aOffset = offsets[0].first
        val bOffset = offsets[1].first
        assertNull(JsonStateOffsets.enclosingState(offsets, aOffset - 1))
        assertEquals("A", JsonStateOffsets.enclosingState(offsets, bOffset - 1))
        assertEquals("B", JsonStateOffsets.enclosingState(offsets, text.length))
    }

    @Test
    fun `duplicate names resolve to the first declaration`() {
        val dup = """{"states":[{"name":"X"},{"name":"X"}]}"""
        val offsets = JsonStateOffsets.nameOffsets(dup, listOf("X"))
        assertEquals(1, offsets.size)
        assertEquals(dup.indexOf("\"X\"") + 1, offsets[0].first)
    }
}
