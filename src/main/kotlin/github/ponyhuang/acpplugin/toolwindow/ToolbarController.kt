package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.toolwindow.ui.chat.ChatViewToolbar
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.swing.JComponent

class ToolbarController(
    project: Project,
    loading: StateFlow<Boolean>,
    connected: StateFlow<Boolean>,
    switching: StateFlow<Boolean>,
    listingSessions: StateFlow<Boolean>,
    isLoading: () -> Boolean,
    isListingSessions: () -> Boolean,
    hasSelectedAgent: () -> Boolean,
    isSessionConnected: () -> Boolean,
    getComposerState: () -> ToolWindowComposerState,
    onNewSession: () -> Unit,
    onShowSessions: () -> Unit,
    onCancel: () -> Unit,
) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val toolbar = ChatViewToolbar(
        project = project,
        isLoading = isLoading,
        isListingSessions = isListingSessions,
        hasSelectedAgent = hasSelectedAgent,
        onNewSession = onNewSession,
        onShowSessions = onShowSessions,
        onCancel = onCancel,
        isSessionConnected = isSessionConnected,
        getComposerState = getComposerState
    )

    val component: JComponent
        get() = toolbar.component

    init {
        scope.launch {
            combine(
                loading,
                connected,
                switching,
                listingSessions
            ) { _, _, _, _ -> Unit }
                .collectLatest {
                    update()
                }
        }
    }

    fun update() {
        toolbar.update()
    }

    override fun dispose() {
        scope.cancel()
        toolbar.dispose()
    }
}
