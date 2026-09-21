package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Activity-scoped state owner; config changes only recreate the UI, never the running Agent session. */

internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
}

