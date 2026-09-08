package com.github.ss.ripgrepsearch.ui

import com.github.ss.ripgrepsearch.search.RipgrepResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel

class FilePreviewPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {
    private var editor: EditorEx? = null
    private var currentFile: VirtualFile? = null
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val placeholder = JBLabel("Select a result to preview")

    init {
        add(placeholder, BorderLayout.CENTER)
    }

    fun clear() {
        currentFile = null
        releaseEditor()
        removeAll()
        add(placeholder, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showResult(result: RipgrepResult) {
        if (!result.virtualFile.isValid) {
            clear()
            return
        }

        val existingEditor = editor
        if (existingEditor == null || currentFile != result.virtualFile) {
            releaseEditor()
            val document = FileDocumentManager.getInstance().getDocument(result.virtualFile) ?: return
            val created = EditorFactory.getInstance().createEditor(document, project, result.virtualFile, true) as EditorEx
            created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, result.virtualFile)
            created.settings.isLineNumbersShown = true
            created.settings.isLineMarkerAreaShown = false
            created.settings.isFoldingOutlineShown = false
            created.contentComponent.isFocusable = false
            created.component.isFocusable = false

            editor = created
            currentFile = result.virtualFile
            removeAll()
            add(created.component, BorderLayout.CENTER)
            revalidate()
        }

        highlightAndScroll(result)
        repaint()
    }

    private fun highlightAndScroll(result: RipgrepResult) {
        val editor = editor ?: return
        val document = editor.document
        if (result.lineIndex !in 0 until document.lineCount) return

        highlighters.forEach { it.dispose() }
        highlighters.clear()

        val lineStart = document.getLineStartOffset(result.lineIndex)
        val lineEnd = document.getLineEndOffset(result.lineIndex)
        val start = (lineStart + result.columnIndex).coerceIn(lineStart, lineEnd)
        val endColumn = result.matchEndColumnIndex ?: result.columnIndex
        val end = (lineStart + endColumn).coerceIn(start, lineEnd)

        val lineAttributes = TextAttributes(
            null,
            JBColor.namedColor("SearchResult.previewLineBackground", Color(0x3A3D41)),
            null,
            null,
            Font.PLAIN,
        )
        highlighters += editor.markupModel.addRangeHighlighter(
            lineStart,
            lineEnd,
            HighlighterLayer.SELECTION - 1,
            lineAttributes,
            HighlighterTargetArea.LINES_IN_RANGE,
        )

        if (end > start) {
            val matchAttributes = editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
            highlighters += editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.SELECTION,
                matchAttributes,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }

        editor.scrollingModel.scrollTo(LogicalPosition(result.lineIndex, result.columnIndex), ScrollType.CENTER)
    }

    private fun releaseEditor() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
    }

    override fun dispose() {
        releaseEditor()
    }
}
