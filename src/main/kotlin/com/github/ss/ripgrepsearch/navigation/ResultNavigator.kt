package com.github.ss.ripgrepsearch.navigation

import com.github.ss.ripgrepsearch.search.RipgrepResult
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project

object ResultNavigator {
    fun navigate(project: Project, result: RipgrepResult) {
        if (!result.virtualFile.isValid) return
        OpenFileDescriptor(project, result.virtualFile, result.lineIndex, result.columnIndex).navigate(true)
    }
}
