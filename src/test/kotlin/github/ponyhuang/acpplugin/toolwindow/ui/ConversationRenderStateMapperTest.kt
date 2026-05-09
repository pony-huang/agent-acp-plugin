package github.ponyhuang.acpplugin.toolwindow.ui
import github.ponyhuang.acpplugin.toolwindow.ui.chat.MessagePromptState

import com.agentclientprotocol.model.StopReason
import github.ponyhuang.acpplugin.services.AcpSessionService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ConversationRenderStateMapperTest {
    private val mapper = ConversationRenderStateMapper()

    @Test
    fun mapsEmptyStateWhenNoVisibleMessagesAndNotLoading() {
        val renderState = mapper.map(
            ConversationViewState(
                messages = emptyList(),
                isLoading = false,
                lastStopReason = null
            ),
            expandedThoughtIds = emptySet()
        )

        assertTrue(renderState.showEmptyState)
        assertEquals(ConversationRenderMode.EMPTY_STATE, renderState.renderMode)
        assertTrue(renderState.renderModels.isEmpty())
    }

    @Test
    fun keepsLatestEmptyAssistantVisibleWhileLoading() {
        val renderState = mapper.map(
            ConversationViewState(
                messages = listOf(
                    message(id = "user-1", role = "user", content = "Prompt"),
                    message(id = "assistant-1", role = "assistant", content = "")
                ),
                isLoading = true,
                lastStopReason = null
            ),
            expandedThoughtIds = emptySet()
        )

        assertFalse(renderState.showEmptyState)
        assertEquals(ConversationRenderMode.MESSAGE_LIST, renderState.renderMode)
        assertEquals(listOf("user-1", "assistant-1"), renderState.renderModels.map { it.message.id })
        assertEquals(MessagePromptState.RUNNING, renderState.renderModels.last().promptState)
    }

    @Test
    fun derivesCompletedPromptStateForLatestAssistant() {
        val renderState = mapper.map(
            ConversationViewState(
                messages = listOf(message(id = "assistant-1", role = "assistant", content = "Done")),
                isLoading = false,
                lastStopReason = StopReason.END_TURN
            ),
            expandedThoughtIds = setOf("assistant-1")
        )

        val model = renderState.renderModels.single()
        assertEquals(MessagePromptState.COMPLETED, model.promptState)
        assertTrue(model.thoughtExpanded)
    }

    @Test
    fun filtersBlankNonLatestAssistantMessage() {
        val renderState = mapper.map(
            ConversationViewState(
                messages = listOf(
                    message(id = "assistant-1", role = "assistant", content = ""),
                    message(id = "assistant-2", role = "assistant", content = "Visible")
                ),
                isLoading = true,
                lastStopReason = null
            ),
            expandedThoughtIds = emptySet()
        )

        assertEquals(listOf("assistant-2"), renderState.renderModels.map { it.message.id })
        assertEquals(MessagePromptState.RUNNING, renderState.renderModels.single().promptState)
    }

    private fun message(
        id: String,
        role: String,
        content: String
    ): AcpSessionService.ChatMessage {
        return AcpSessionService.ChatMessage(
            id = id,
            role = role,
            content = content
        )
    }
}
