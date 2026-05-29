package tech.zacik.workflowviz

import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Renders PlantUML source to an SVG string via PlantUML's in-process engine. */
object PlantUmlRenderer {

    /**
     * PlantUML's Smetana layout occasionally fails (e.g. `JUtils.qsort`
     * `IllegalStateException`) on certain workflow shapes — but instead of
     * throwing, it returns a perfectly valid SVG whose only content is the
     * error message. Detecting those marker strings lets the caller fall
     * back to the previous good render rather than displaying the stack
     * trace as if it were a diagram.
     */
    private val ERROR_MARKERS = listOf(
        "An error has occured", // PlantUML's literal typo
        "subproject Smetana is not finished",
    )

    fun toSvg(puml: String): String {
        val reader = SourceStringReader(puml)
        ByteArrayOutputStream().use { out ->
            // Smetana (set via !pragma in the source) → no graphviz needed.
            reader.outputImage(out, FileFormatOption(FileFormat.SVG))
            val svg = out.toString(StandardCharsets.UTF_8.name())
            if (ERROR_MARKERS.any { svg.contains(it) }) {
                throw IllegalStateException("PlantUML rendering failed (Smetana). Keeping last good diagram.")
            }
            return svg
        }
    }
}
