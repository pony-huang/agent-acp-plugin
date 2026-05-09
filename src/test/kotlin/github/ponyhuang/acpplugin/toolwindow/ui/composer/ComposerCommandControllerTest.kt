package github.ponyhuang.acpplugin.toolwindow.ui.composer

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ComposerCommandControllerTest {
    private val controller = ComposerCommandController().apply {
        updateCommands(
            listOf(
                ComposerCommandItem("help", "Show available commands"),
                ComposerCommandItem("review", "Review current diff"),
                ComposerCommandItem("reset", "Clear the conversation")
            )
        )
    }

    @Test
    fun showsSuggestionsOnlyForSingleSlashTokenWhenIdle() {
        assertTrue(controller.shouldShowSuggestions("/", isBusy = false))
        assertTrue(controller.shouldShowSuggestions("   /re", isBusy = false))
        assertFalse(controller.shouldShowSuggestions("hello", isBusy = false))
        assertFalse(controller.shouldShowSuggestions("/review now", isBusy = false))
        assertFalse(controller.shouldShowSuggestions("/", isBusy = true))
    }

    @Test
    fun filtersByNamePrefixOrDescriptionText() {
        assertEquals(listOf("help"), controller.filterCommands("/hel").map { it.name })
        assertEquals(listOf("review"), controller.filterCommands("/diff").map { it.name })
        assertEquals(listOf("help", "review", "reset"), controller.filterCommands("/").map { it.name })
    }

    @Test
    fun formatsAppliedCommandText() {
        assertEquals("/help ", controller.commandText(ComposerCommandItem("help", "Show help")))
    }
}
