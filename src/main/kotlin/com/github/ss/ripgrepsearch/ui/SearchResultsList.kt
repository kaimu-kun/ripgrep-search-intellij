package com.github.ss.ripgrepsearch.ui

import com.github.ss.ripgrepsearch.search.RipgrepResult
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.ListSelectionModel

class SearchResultsList(
    private val onSelected: (RipgrepResult?) -> Unit,
    private val onConfirmed: (RipgrepResult) -> Unit,
) {
    private val model = CollectionListModel<RipgrepResult>()
    val component: JBList<RipgrepResult> = JBList(model)

    init {
        component.emptyText.text = "Type to search"
        component.selectionMode = ListSelectionModel.SINGLE_SELECTION
        component.cellRenderer = Renderer()
        component.addListSelectionListener {
            if (!it.valueIsAdjusting) onSelected(component.selectedValue)
        }
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) component.selectedValue?.let(onConfirmed)
            }
        })
    }

    fun clear(emptyText: String) {
        model.removeAll()
        component.emptyText.text = emptyText
        onSelected(null)
    }

    fun setEmptyText(text: String) {
        component.emptyText.text = text
    }

    fun addResults(results: List<RipgrepResult>) {
        if (results.isEmpty()) return
        val shouldSelectFirst = model.size == 0
        model.add(results)
        if (shouldSelectFirst) component.selectedIndex = 0
    }

    fun selectedResult(): RipgrepResult? = component.selectedValue

    fun selectRelative(delta: Int) {
        if (model.size == 0) return
        val current = component.selectedIndex.takeIf { it >= 0 } ?: 0
        component.selectedIndex = (current + delta).coerceIn(0, model.size - 1)
        component.ensureIndexIsVisible(component.selectedIndex)
    }

    fun page(delta: Int) {
        val visibleRows = component.visibleRect.height / component.fixedCellHeight.coerceAtLeast(1)
        selectRelative(delta * visibleRows.coerceAtLeast(8))
    }

    private class Renderer : ColoredListCellRenderer<RipgrepResult>() {
        override fun customizeCellRenderer(
            list: JList<out RipgrepResult>,
            value: RipgrepResult?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            append(value.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ${value.lineNumberOneBased}:${value.columnNumberOneBased}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            append("  ${value.relativePath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("\n")
            append(value.lineText.take(240), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
