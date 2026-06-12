package tech.zacik.workflowviz

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Encoding for the `swjson://<state>` anchors PlantUML embeds in the SVG.
 *
 * State names travel percent-encoded: PlantUML's `[[uri]]` link syntax treats
 * a space as the URL/label separator (silently truncating the href), and raw
 * quotes or newlines would break out of the surrounding markup entirely. The
 * encoder never emits `+` (space becomes `%20`), so decode is a plain
 * URL-decode.
 */
object StateUri {
    const val SCHEME = "swjson://"

    /** Full anchor URI for [name], e.g. `swjson://Fetch%20Data`. */
    fun encode(name: String): String =
        SCHEME + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")

    /** State name from an anchor URI produced by [encode]. */
    fun decode(uri: String): String =
        URLDecoder.decode(uri.removePrefix(SCHEME), Charsets.UTF_8)
}
