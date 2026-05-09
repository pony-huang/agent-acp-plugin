package github.ponyhuang.acpplugin.toolwindow.ui.composer

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ComposerControlStateTest {
    @Test
    fun selectorStateMatchesConnectedIdleAvailability() {
        val state = ComposerSelectorState.from(
            isSessionConnected = true,
            isBusy = false,
            hasSelectedPlan = true,
            hasSelectedModel = true
        )

        assertTrue(state.agentEnabled)
        assertTrue(state.planEnabled)
        assertTrue(state.modelEnabled)
    }

    @Test
    fun selectorStateDisablesSessionSelectorsWhenBusy() {
        val state = ComposerSelectorState.from(
            isSessionConnected = true,
            isBusy = true,
            hasSelectedPlan = true,
            hasSelectedModel = true
        )

        assertFalse(state.agentEnabled)
        assertFalse(state.planEnabled)
        assertFalse(state.modelEnabled)
    }

    @Test
    fun sendActionRequiresConnectedIdleSession() {
        assertTrue(
            ComposerControlStatePresenter.present(
                isSessionConnected = true,
                isBusy = false,
                hasSelectedAgent = true
            ).sendEnabled
        )
        assertFalse(
            ComposerControlStatePresenter.present(
                isSessionConnected = true,
                isBusy = true,
                hasSelectedAgent = true
            ).sendEnabled
        )
    }
}
