package github.ponyhuang.acpplugin.toolwindow


enum class ToolWindowComposerState {
    IDLE,
    CONNECTING,
    SENDING,
}

internal fun deriveToolWindowComposerState(
    loading: Boolean,
    connected: Boolean,
    switching: Boolean
): ToolWindowComposerState {
    return when {
        switching -> ToolWindowComposerState.CONNECTING
        loading && !connected -> ToolWindowComposerState.CONNECTING
        loading -> ToolWindowComposerState.SENDING
        else -> ToolWindowComposerState.IDLE
    }
}
