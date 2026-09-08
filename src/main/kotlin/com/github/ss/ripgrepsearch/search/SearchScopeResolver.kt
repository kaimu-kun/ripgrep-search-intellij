package com.github.ss.ripgrepsearch.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

object SearchScopeResolver {
    fun resolve(projectRoot: Path, input: String): SearchScopeValidation {
        val root = projectRoot.toAbsolutePath().normalize()
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == ".") return validRoot(root)

        val normalizedInput = trimmed.replace('\\', '/')
        val rawPath = Path.of(normalizedInput)
        val absolutePath = if (rawPath.isAbsolute) rawPath.normalize() else root.resolve(rawPath).normalize()

        if (!absolutePath.startsWith(root)) {
            return SearchScopeValidation.Invalid("Search path must stay inside the project")
        }
        if (!Files.exists(absolutePath)) {
            return SearchScopeValidation.Invalid("Search path does not exist: $trimmed")
        }
        if (!Files.isDirectory(absolutePath)) {
            return SearchScopeValidation.Invalid("Search path must be a directory: $trimmed")
        }

        val relative = root.relativize(absolutePath).pathString.replace('\\', '/').ifEmpty { "." }
        return SearchScopeValidation.Valid(rgArgument = relative, displayPath = relative)
    }

    private fun validRoot(root: Path): SearchScopeValidation =
        if (Files.isDirectory(root)) {
            SearchScopeValidation.Valid(rgArgument = ".", displayPath = ".")
        } else {
            SearchScopeValidation.Invalid("Project root is not a usable directory")
        }
}

sealed interface SearchScopeValidation {
    data class Valid(val rgArgument: String, val displayPath: String) : SearchScopeValidation
    data class Invalid(val message: String) : SearchScopeValidation
}
