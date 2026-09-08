package com.github.ss.ripgrepsearch.search

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

data class RipgrepSearchRequest(
    val query: String,
    val projectRoot: Path,
    val maxResults: Int = 500,
)

data class RipgrepResultCandidate(
    val relativePath: String,
    val lineNumberOneBased: Int,
    val lineText: String,
    val matchStartColumnIndex: Int?,
    val matchEndColumnIndex: Int?,
) {
    val lineIndex: Int = (lineNumberOneBased - 1).coerceAtLeast(0)
    val columnIndex: Int = (matchStartColumnIndex ?: 0).coerceAtLeast(0)
    val columnNumberOneBased: Int = columnIndex + 1
}

data class RipgrepResult(
    val relativePath: String,
    val absolutePath: Path,
    val virtualFile: VirtualFile,
    val lineNumberOneBased: Int,
    val columnNumberOneBased: Int,
    val lineIndex: Int,
    val columnIndex: Int,
    val lineText: String,
    val matchStartColumnIndex: Int?,
    val matchEndColumnIndex: Int?,
) {
    val fileName: String = virtualFile.name
}

sealed interface RipgrepSearchEvent {
    data object Started : RipgrepSearchEvent
    data class Results(val items: List<RipgrepResult>) : RipgrepSearchEvent
    data class Capped(val maxResults: Int) : RipgrepSearchEvent
    data object Completed : RipgrepSearchEvent
    data object NoMatches : RipgrepSearchEvent
    data class Failed(val message: String) : RipgrepSearchEvent
}
