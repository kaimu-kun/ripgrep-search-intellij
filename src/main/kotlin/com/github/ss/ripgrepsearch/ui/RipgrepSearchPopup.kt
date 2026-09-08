package com.github.ss.ripgrepsearch.ui

import com.github.ss.ripgrepsearch.navigation.ResultNavigator
import com.github.ss.ripgrepsearch.notifications.RipgrepNotifications
import com.github.ss.ripgrepsearch.search.RipgrepSearchEvent
import com.github.ss.ripgrepsearch.search.RipgrepSearchRequest
import com.github.ss.ripgrepsearch.search.RipgrepSearchService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@OptIn(FlowPreview::class)
class RipgrepSearchPopup(
    private val project: Project,
    private val dataContext: DataContext,
    private val projectRoot: Path,
) {
    private val popupDisposable = Disposer.newDisposable("RipgrepSearchPopup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queryFlow = MutableStateFlow("")
    private val generation = AtomicInteger(0)
    private val previousFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Type to search")
    private val previewPanel = FilePreviewPanel(project)
    private var confirmed = false
    private var closed = false
    private lateinit var popup: JBPopup

    private val resultsList = SearchResultsList(
        onSelected = { result ->
            if (result == null) previewPanel.clear() else previewPanel.showResult(result)
        },
        onConfirmed = { confirm(it) },
    )

    fun show() {
        val content = createContent()
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField.textEditor)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setDimensionServiceKey(project, "RipgrepSearchPopup", true)
            .createPopup()

        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                closed = true
                scope.cancel()
                Disposer.dispose(popupDisposable)
                if (!confirmed) previousFocusOwner?.requestFocusInWindow()
            }
        })

        Disposer.register(popupDisposable, Disposable { scope.cancel() })
        Disposer.register(popupDisposable, previewPanel)

        installSearchListener()
        installKeyboardActions(content)
        startSearchLoop()

        popup.showInBestPositionFor(dataContext)
        SwingUtilities.invokeLater { searchField.textEditor.requestFocusInWindow() }
    }

    private fun createContent(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(1100, 700)
            border = JBUI.Borders.empty(8)
        }

        val searchPanel = NonOpaquePanel(BorderLayout(8, 0)).apply {
            add(JBLabel("Search:"), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(8)
        }

        val splitter = Splitter(false, 0.38f).apply {
            firstComponent = JBScrollPane(resultsList.component)
            secondComponent = previewPanel
        }

        root.add(searchPanel, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    private fun installSearchListener() {
        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateQuery()
            override fun removeUpdate(e: DocumentEvent) = updateQuery()
            override fun changedUpdate(e: DocumentEvent) = updateQuery()
        })
    }

    private fun updateQuery() {
        queryFlow.value = searchField.text
    }

    private fun installKeyboardActions(root: JComponent) {
        val targets = listOf(searchField.textEditor, root, resultsList.component)
        targets.forEach { component ->
            bind(component, KeyEvent.VK_DOWN, "rg.down") { resultsList.selectRelative(1) }
            bind(component, KeyEvent.VK_UP, "rg.up") { resultsList.selectRelative(-1) }
            bind(component, KeyEvent.VK_PAGE_DOWN, "rg.pageDown") { resultsList.page(1) }
            bind(component, KeyEvent.VK_PAGE_UP, "rg.pageUp") { resultsList.page(-1) }
            bind(component, KeyEvent.VK_ENTER, "rg.enter") { resultsList.selectedResult()?.let { confirm(it) } }
            bind(component, KeyEvent.VK_ESCAPE, "rg.escape") { popup.cancel() }
        }
    }

    private fun bind(component: JComponent, keyCode: Int, name: String, action: () -> Unit) {
        component.inputMap.put(KeyStroke.getKeyStroke(keyCode, 0), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    private fun startSearchLoop(): Job = scope.launch {
        queryFlow
            .debounce(125)
            .distinctUntilChanged()
            .collectLatest { query ->
                val currentGeneration = generation.incrementAndGet()
                if (query.isBlank()) {
                    onEdt(currentGeneration) {
                        statusLabel.text = "Type to search"
                        resultsList.clear("Type to search")
                    }
                    return@collectLatest
                }

                onEdt(currentGeneration) {
                    statusLabel.text = "Searching..."
                    resultsList.clear("Searching...")
                }

                val request = RipgrepSearchRequest(query = query, projectRoot = projectRoot)
                project.getService(RipgrepSearchService::class.java).search(request).collect { event ->
                    onEdt(currentGeneration) { handleSearchEvent(event) }
                }
            }
    }

    private fun handleSearchEvent(event: RipgrepSearchEvent) {
        when (event) {
            RipgrepSearchEvent.Started -> statusLabel.text = "Searching..."
            is RipgrepSearchEvent.Results -> {
                resultsList.addResults(event.items)
                statusLabel.text = "${resultsList.component.model.size} results"
            }
            is RipgrepSearchEvent.Capped -> {
                resultsList.setEmptyText("Showing first ${event.maxResults} results")
                statusLabel.text = "Showing first ${event.maxResults} results"
            }
            RipgrepSearchEvent.Completed -> {
                if (resultsList.component.model.size == 0) resultsList.setEmptyText("No matches")
                statusLabel.text = "${resultsList.component.model.size} results"
            }
            RipgrepSearchEvent.NoMatches -> {
                if (resultsList.component.model.size == 0) resultsList.setEmptyText("No matches")
                statusLabel.text = "No matches"
            }
            is RipgrepSearchEvent.Failed -> {
                resultsList.setEmptyText(event.message)
                statusLabel.text = event.message
                if (event.message.contains("not found on PATH")) {
                    RipgrepNotifications.error(project, event.message)
                }
            }
        }
    }

    private fun onEdt(expectedGeneration: Int, action: () -> Unit) {
        SwingUtilities.invokeLater {
            if (!closed && generation.get() == expectedGeneration) action()
        }
    }

    private fun confirm(result: com.github.ss.ripgrepsearch.search.RipgrepResult) {
        confirmed = true
        popup.cancel()
        ResultNavigator.navigate(project, result)
    }
}
