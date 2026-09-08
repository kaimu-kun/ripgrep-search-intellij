package com.github.ss.ripgrepsearch.search

import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchScopeResolverTest {
    @Test
    fun blankBecomesProjectRoot() {
        val root = createTempDirectory()

        val result = assertIs<SearchScopeValidation.Valid>(SearchScopeResolver.resolve(root, ""))

        assertEquals(".", result.rgArgument)
    }

    @Test
    fun dotRemainsProjectRoot() {
        val root = createTempDirectory()

        val result = assertIs<SearchScopeValidation.Valid>(SearchScopeResolver.resolve(root, "."))

        assertEquals(".", result.rgArgument)
    }

    @Test
    fun relativeDirectoryIsAccepted() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("src/main"))

        val result = assertIs<SearchScopeValidation.Valid>(SearchScopeResolver.resolve(root, "src/main"))

        assertEquals("src/main", result.rgArgument)
    }

    @Test
    fun spacesArePreserved() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("src/main kotlin"))

        val result = assertIs<SearchScopeValidation.Valid>(SearchScopeResolver.resolve(root, "src/main kotlin"))

        assertEquals("src/main kotlin", result.rgArgument)
    }

    @Test
    fun backslashesAreNormalized() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("src/main"))

        val result = assertIs<SearchScopeValidation.Valid>(SearchScopeResolver.resolve(root, "src\\main"))

        assertEquals("src/main", result.rgArgument)
    }

    @Test
    fun parentEscapeIsRejected() {
        val root = createTempDirectory()

        val result = SearchScopeResolver.resolve(root, "../outside")

        assertIs<SearchScopeValidation.Invalid>(result)
    }

    @Test
    fun absolutePathInsideProjectBecomesRelative() {
        val root = createTempDirectory()
        val dir = Files.createDirectories(root.resolve("docs/reference"))

        val result = assertIs<SearchScopeValidation.Valid>(SearchScopeResolver.resolve(root, dir.pathString))

        assertEquals("docs/reference", result.rgArgument)
    }

    @Test
    fun absolutePathOutsideProjectIsRejected() {
        val root = createTempDirectory()
        val outside = createTempDirectory()

        val result = SearchScopeResolver.resolve(root, outside.pathString)

        assertIs<SearchScopeValidation.Invalid>(result)
    }

    @Test
    fun filePathIsRejected() {
        val root = createTempDirectory()
        root.resolve("file.txt").createFile()

        val result = SearchScopeResolver.resolve(root, "file.txt")

        assertIs<SearchScopeValidation.Invalid>(result)
    }
}
