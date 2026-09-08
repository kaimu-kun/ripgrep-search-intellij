package com.github.ss.ripgrepsearch.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Base64

class RipgrepJsonParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseLine(line: String): ParseResult {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }
            .getOrElse { return ParseResult.Malformed }

        if (root.string("type") != "match") return ParseResult.Ignored

        val data = root.obj("data") ?: return ParseResult.Malformed
        val relativePath = data.arbitraryText("path") ?: return ParseResult.Malformed
        val lineNumber = data.primitive("line_number")?.intOrNull ?: return ParseResult.Malformed
        val rawLineText = data.arbitraryText("lines") ?: return ParseResult.Malformed
        val lineText = rawLineText.trimEnd('\n').trimEnd('\r')
        val submatches = data.array("submatches") ?: JsonArray(emptyList())

        val candidates = if (submatches.isEmpty()) {
            listOf(
                RipgrepResultCandidate(
                    relativePath = normalizeRelativePath(relativePath),
                    lineNumberOneBased = lineNumber,
                    lineText = lineText,
                    matchStartColumnIndex = null,
                    matchEndColumnIndex = null,
                ),
            )
        } else {
            submatches.mapNotNull { element ->
                val submatch = element as? JsonObject ?: return@mapNotNull null
                val startByte = submatch.primitive("start")?.intOrNull ?: return@mapNotNull null
                val endByte = submatch.primitive("end")?.intOrNull ?: return@mapNotNull null
                RipgrepResultCandidate(
                    relativePath = normalizeRelativePath(relativePath),
                    lineNumberOneBased = lineNumber,
                    lineText = lineText,
                    matchStartColumnIndex = utf8ByteOffsetToUtf16Index(rawLineText, startByte),
                    matchEndColumnIndex = utf8ByteOffsetToUtf16Index(rawLineText, endByte),
                )
            }
        }

        return ParseResult.Matches(candidates)
    }

    private fun normalizeRelativePath(path: String): String =
        path.removePrefix("./").replace('\\', '/')

    companion object {
        fun utf8ByteOffsetToUtf16Index(text: String, byteOffset: Int): Int {
            var bytesSeen = 0
            var charIndex = 0
            while (charIndex < text.length && bytesSeen < byteOffset) {
                val codePoint = text.codePointAt(charIndex)
                val charCount = Character.charCount(codePoint)
                val byteCount = when {
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    codePoint <= 0xFFFF -> 3
                    else -> 4
                }
                if (bytesSeen + byteCount > byteOffset) break
                bytesSeen += byteCount
                charIndex += charCount
            }
            return charIndex.coerceIn(0, text.length)
        }
    }
}

sealed interface ParseResult {
    data class Matches(val candidates: List<RipgrepResultCandidate>) : ParseResult
    data object Ignored : ParseResult
    data object Malformed : ParseResult
}

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

private fun JsonObject.primitive(name: String): JsonPrimitive? = get(name)?.jsonPrimitive

private fun JsonObject.string(name: String): String? = primitive(name)?.contentOrNull

private fun JsonObject.arbitraryText(name: String): String? {
    val container = obj(name) ?: return null
    container.string("text")?.let { return it }
    val bytes = container.string("bytes") ?: return null
    return runCatching {
        String(Base64.getDecoder().decode(bytes), StandardCharsets.UTF_8)
    }.getOrNull()
}
