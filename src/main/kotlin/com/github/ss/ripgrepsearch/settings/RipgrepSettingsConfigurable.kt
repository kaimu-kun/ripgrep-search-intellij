package com.github.ss.ripgrepsearch.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class RipgrepSettingsConfigurable : SearchableConfigurable {
    private var panel: JPanel? = null
    private var rgPathField: TextFieldWithBrowseButton? = null

    override fun getId(): String = "settings.ripgrep.search"

    override fun getDisplayName(): String = "Ripgrep Search"


    override fun createComponent(): JComponent {
        val field = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select rg Executable")
                .withDescription("Choose the ripgrep executable. Leave blank to use rg from PATH.")
            addBrowseFolderListener(null, descriptor)
        }
        rgPathField = field

        val hint = JBLabel("Leave blank to use rg from PATH.")

        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(
                FormBuilder.createFormBuilder()
                    .addLabeledComponent("Path to rg executable:", field, 1, false)
                    .addComponentToRightColumn(hint, 1)
                    .addComponentFillVertically(JPanel(), 0)
                    .panel,
                BorderLayout.NORTH,
            )
        }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean = rgPathField?.text.orEmpty() != RipgrepSettings.getInstance().state.rgPath

    override fun apply() {
        RipgrepSettings.getInstance().state.rgPath = rgPathField?.text.orEmpty().trim()
    }

    override fun reset() {
        rgPathField?.text = RipgrepSettings.getInstance().state.rgPath
    }

    override fun disposeUIResources() {
        panel = null
        rgPathField = null
    }
}
