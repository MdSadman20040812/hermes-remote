package com.hermes.mobile.core.artifact

import com.hermes.mobile.domain.model.ArtifactState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finds renderable components in agent output and compiles them on the PC.
 *
 * The agent does not need a special protocol: it writes a fenced block the way
 * it already writes code. A ```tsx / ```jsx fence that exports a component is
 * treated as a renderable artifact; everything else stays an ordinary code
 * block. That keeps the feature invisible when it is not wanted and removes any
 * need for the model to learn a new output format.
 *
 * Compilation is a PC round trip (esbuild, ~300 ms) rather than shipping a
 * transpiler into the app: the PC already has Node, and a ~3 MB Babel bundle
 * parsed on every render would be slower and heavier than the network hop.
 */
object ArtifactDetector {

    /** Languages that can produce a rendered component. */
    private val RENDERABLE = setOf("tsx", "jsx")

    private val FENCE = Regex(
        "```(\\w+)?[ \\t]*\\r?\\n(.*?)```",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * A fence is only an artifact if it actually renders something. A `tsx`
     * fence containing a type definition or a snippet of props is code, not a
     * component, and turning it into an empty WebView would be worse than
     * leaving it as text.
     */
    private fun isComponent(src: String): Boolean {
        val hasExport = src.contains("export default") ||
            Regex("export\\s+(function|const)\\s+\\w+").containsMatchIn(src)
        val hasJsx = src.contains("</") || Regex("<\\w+[^>]*/>").containsMatchIn(src)
        return hasExport && hasJsx
    }

    /** Derive a card title from the component name, falling back to a generic. */
    private fun titleOf(src: String): String {
        Regex("export\\s+default\\s+function\\s+(\\w+)").find(src)?.let {
            return humanise(it.groupValues[1])
        }
        Regex("(?:function|const)\\s+(\\w+)\\s*[=(]").find(src)?.let {
            return humanise(it.groupValues[1])
        }
        return "Component"
    }

    private fun humanise(name: String): String =
        name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }

    data class Found(val artifact: ArtifactState, val range: IntRange)

    /** All renderable fences in [text], in document order. */
    fun scan(text: String, keyPrefix: String): List<Found> =
        FENCE.findAll(text).mapNotNull { m ->
            val lang = (m.groupValues[1].ifBlank { "" }).lowercase()
            val body = m.groupValues[2]
            if (lang !in RENDERABLE || !isComponent(body)) return@mapNotNull null
            Found(
                ArtifactState(
                    id = "$keyPrefix-${m.range.first}",
                    source = body.trimEnd(),
                    lang = lang,
                    title = titleOf(body),
                    compiling = true,
                ),
                m.range,
            )
        }.toList()

    /** Request body for the PC artifact server. */
    fun compileRequest(a: ArtifactState): JsonObject = buildJsonObject {
        put("source", JsonPrimitive(a.source))
        put("lang", JsonPrimitive(a.lang))
    }

    /** Apply a `/compile` response to the pending artifact. */
    fun applyResponse(a: ArtifactState, body: JsonObject, baseUrl: String): ArtifactState {
        val ok = body["ok"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (!ok) {
            return a.copy(
                compiling = false,
                error = body["error"]?.jsonPrimitive?.content ?: "compile failed",
            )
        }
        val path = body["url"]?.jsonPrimitive?.content ?: return a.copy(
            compiling = false,
            error = "server returned no artifact url",
        )
        return a.copy(
            compiling = false,
            error = null,
            url = baseUrl.trimEnd('/') + path,
            compileMs = body["ms"]?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }
}
