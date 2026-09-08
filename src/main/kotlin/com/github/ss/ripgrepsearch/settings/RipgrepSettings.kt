package com.github.ss.ripgrepsearch.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "RipgrepSearchSettings",
    storages = [Storage("ripgrepSearch.xml", roamingType = RoamingType.DISABLED)],
)
class RipgrepSettings : PersistentStateComponent<RipgrepSettings.State> {
    data class State(
        var rgPath: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): RipgrepSettings = ApplicationManager.getApplication().getService(RipgrepSettings::class.java)
    }
}
