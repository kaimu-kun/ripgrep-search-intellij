package com.github.ss.ripgrepsearch.ui

import com.github.ss.ripgrepsearch.navigation.ResultNavigator
import com.github.ss.ripgrepsearch.notifications.RipgrepNotifications
import com.github.ss.ripgrepsearch.search.RipgrepSearchEvent
import com.github.ss.ripgrepsearch.search.RipgrepSearchRequest
import com.github.ss.ripgrepsearch.search.RipgrepSearchService
import com.github.ss.ripgrepsearch.search.SearchScopeResolver
import com.github.ss.ripgrepsearch.search.SearchScopeValidation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
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
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@OptIn(FlowPreview::class)
class RipgrepSearchDialog(
    private val project: Project,
    private val projectRoot: Path,
) : DialogWrapper(project, false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val searchInputFlow = MutableStateFlow(SearchInput(query = "", scopePath = "."))
    private val generation = AtomicInteger(0)
    private val previousFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

    private val searchField = SearchTextField()
    private val scopeField = TextFieldWithBrowseButton { chooseSearchScope() }
    private val statusLabel = JBLabel("Type to search")
    private val previewPanel = FilePreviewPanel(project)
    private var confirmed = false
    private var closed = false

    private val resultsList = SearchResultsList(
        onSelected = { result ->
            if (result == null) previewPanel.clear() else previewPanel.showResult(result)
        },
        onConfirmed = { confirm(it) },
    )

    init {
        title = "Ripgrep Search"
        setModal(false)
        setResizable(true)
        init()

        Disposer.register(disposable, Disposable { scope.cancel() })
        Disposer.register(disposable, previewPanel)

        configureScopeField()
        installInputListeners()
        startSearchLoop()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(1100, 700)
            border = JBUI.Borders.empty(8)
        }

        val searchPanel = NonOpaquePanel(BorderLayout(8, 0)).apply {
            add(JBLabel("Search:"), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(8)
        }

        val scopePanel = NonOpaquePanel(BorderLayout(8, 0)).apply {
            add(JBLabel("In:"), BorderLayout.WEST)
            add(scopeField, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(8)
        }

        val splitter = Splitter(false, 0.38f).apply {
            firstComponent = JBScrollPane(resultsList.component)
            secondComponent = previewPanel
        }

        val topPanel = NonOpaquePanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(scopePanel, BorderLayout.CENTER)
        }

        root.add(topPanel, BorderLayout.NORTH)
        root.add(splitter, BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        installKeyboardActions(root)
        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun createActions(): Array<Action> = emptyArray()

    override fun show() {
        super.show()
        SwingUtilities.invokeLater { searchField.textEditor.requestFocusInWindow() }
    }

    override fun doCancelAction() {
        close(CANCEL_EXIT_CODE)
    }

    override fun dispose() {
        closed = true
        scope.cancel()
        super.dispose()
        if (!confirmed) previousFocusOwner?.requestFocusInWindow()
    }

    private fun configureScopeField() {
        scopeField.text = "."
    }

    private fun chooseSearchScope() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Search Directory")
            .withDescription("Choose a directory inside the project to search.")
        val selected = FileChooser.chooseFile(descriptor, project, initialBrowseDirectory()) ?: return
        when (val resolved = SearchScopeResolver.resolve(projectRoot, selected.path)) {
            is SearchScopeValidation.Valid -> scopeField.text = resolved.displayPath
            is SearchScopeValidation.Invalid -> statusLabel.text = resolved.message
        }
    }

    private fun initialBrowseDirectory(): com.intellij.openapi.vfs.VirtualFile? {
        val resolved = SearchScopeResolver.resolve(projectRoot, scopeField.text)
        val path = if (resolved is SearchScopeValidation.Valid) {
            projectRoot.resolve(resolved.rgArgument).normalize()
        } else {
            projectRoot
        }
        return ReadAction.computeBlocking<com.intellij.openapi.vfs.VirtualFile?, RuntimeException> {
            LocalFileSystem.getInstance().findFileByNioFile(path)
        }
    }

    private fun installInputListeners() {
        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateSearchInput()
            override fun removeUpdate(e: DocumentEvent) = updateSearchInput()
            override fun changedUpdate(e: DocumentEvent) = updateSearchInput()
        })
        scopeField.textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateSearchInput()
            override fun removeUpdate(e: DocumentEvent) = updateSearchInput()
            override fun changedUpdate(e: DocumentEvent) = updateSearchInput()
        })
    }

    private fun updateSearchInput() {
        searchInputFlow.value = SearchInput(query = searchField.text, scopePath = scopeField.text)
    }

    private fun installKeyboardActions(root: JComponent) {
        bind(root, KeyEvent.VK_DOWN, "rg.down") { resultsList.selectRelative(1) }
        bind(root, KeyEvent.VK_UP, "rg.up") { resultsList.selectRelative(-1) }
        bind(root, KeyEvent.VK_PAGE_DOWN, "rg.pageDown") { resultsList.page(1) }
        bind(root, KeyEvent.VK_PAGE_UP, "rg.pageUp") { resultsList.page(-1) }
        bind(root, KeyEvent.VK_ENTER, "rg.enter") { resultsList.selectedResult()?.let { confirm(it) } }
        bind(root, KeyEvent.VK_ESCAPE, "rg.escape") { doCancelAction() }
    }

    private fun bind(component: JComponent, keyCode: Int, name: String, action: () -> Unit) {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(keyCode, 0), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    private fun startSearchLoop(): Job = scope.launch {
        searchInputFlow
            .debounce(125)
            .distinctUntilChanged()
            .collectLatest { input ->
                val currentGeneration = generation.incrementAndGet()
                val scopeValidation = SearchScopeResolver.resolve(projectRoot, input.scopePath)
                if (scopeValidation is SearchScopeValidation.Invalid) {
                    onEdt(currentGeneration) {
                        statusLabel.text = scopeValidation.message
                        resultsList.clear(scopeValidation.message)
                    }
                    return@collectLatest
                }

                val searchScope = scopeValidation as SearchScopeValidation.Valid
                if (input.query.isBlank()) {
                    onEdt(currentGeneration) {
                        statusLabel.text = "Type to search in ${searchScope.displayPath}"
                        resultsList.clear("Type to search")
                    }
                    return@collectLatest
                }

                onEdt(currentGeneration) {
                    statusLabel.text = "Searching in ${searchScope.displayPath}..."
                    resultsList.clear("Searching in ${searchScope.displayPath}...")
                }

                val request = RipgrepSearchRequest(
                    query = input.query,
                    projectRoot = projectRoot,
                    searchPath = searchScope.rgArgument,
                )
                project.getService(RipgrepSearchService::class.java).search(request).collect { event ->
                    onEdt(currentGeneration) { handleSearchEvent(event, searchScope.displayPath) }
                }
            }
    }

    private fun handleSearchEvent(event: RipgrepSearchEvent, displayPath: String) {
        when (event) {
            RipgrepSearchEvent.Started -> statusLabel.text = "Searching in $displayPath..."
            is RipgrepSearchEvent.Results -> {
                resultsList.addResults(event.items)
                statusLabel.text = "${resultsList.component.model.size} results in $displayPath"
            }
            is RipgrepSearchEvent.Capped -> {
                resultsList.setEmptyText("Showing first ${event.maxResults} results in $displayPath")
                statusLabel.text = "Showing first ${event.maxResults} results in $displayPath"
            }
            RipgrepSearchEvent.Completed -> {
                if (resultsList.component.model.size == 0) resultsList.setEmptyText("No matches in $displayPath")
                statusLabel.text = "${resultsList.component.model.size} results in $displayPath"
            }
            RipgrepSearchEvent.NoMatches -> {
                if (resultsList.component.model.size == 0) resultsList.setEmptyText("No matches in $displayPath")
                statusLabel.text = "No matches in $displayPath"
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
        ApplicationManager.getApplication().invokeLater({
            if (!closed && generation.get() == expectedGeneration) action()
        }, ModalityState.defaultModalityState())
    }

    private fun confirm(result: com.github.ss.ripgrepsearch.search.RipgrepResult) {
        confirmed = true
        close(OK_EXIT_CODE)
        ResultNavigator.navigate(project, result)
    }

    private data class SearchInput(val query: String, val scopePath: String)
}
