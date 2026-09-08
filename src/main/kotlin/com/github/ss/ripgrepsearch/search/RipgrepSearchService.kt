package com.github.ss.ripgrepsearch.search

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

@Service(Service.Level.PROJECT)
class RipgrepSearchService(
    @Suppress("unused") private val project: Project,
    @Suppress("unused") private val coroutineScope: CoroutineScope,
) {
    private val runner = RipgrepProcessRunner()

    fun search(request: RipgrepSearchRequest): Flow<RipgrepSearchEvent> = runner.search(request)
}
