package github.ponyhuang.acpplugin.toolwindow.action

import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.Icon
import javax.swing.JComponent

class AgentComboBoxActionTest : BasePlatformTestCase() {

    fun testPopupActionsCarryAgentIcons() {
        val firstIcon = AllIcons.Actions.Execute
        val secondIcon = AllIcons.Actions.Suspend
        val action = AgentComboBoxAction(
            listOf(
                agentItem("a", "Agent A", firstIcon),
                agentItem("b", "Agent B", secondIcon),
            )
        )
        val component = action.createCustomComponent(action.templatePresentation, "test")
        val group = popupGroup(action, component)

        val popupActions = group.childActionsOrStubs.map { it as AnAction }
        assertEquals(firstIcon, popupActions[0].templatePresentation.icon)
        assertEquals(secondIcon, popupActions[1].templatePresentation.icon)
    }

    fun testSelectionUpdatesButtonAndPresentationIcon() {
        val selectedIcon = AllIcons.Actions.Execute
        val action = AgentComboBoxAction(listOf(agentItem("a", "Agent A", selectedIcon)))
        val component = action.createCustomComponent(action.templatePresentation, "test")
        val group = popupGroup(action, component)

        val childAction = group.childActionsOrStubs.single() as AnAction
        childAction.actionPerformed(
            AnActionEvent.createFromAnAction(
                childAction,
                null,
                ActionPlaces.UNKNOWN,
                SimpleDataContext.EMPTY_CONTEXT
            )
        )

        val button = component as ComboBoxAction.ComboBoxButton
        assertEquals("Agent A", button.text)
        assertEquals(selectedIcon, button.icon)
        assertEquals("Agent A", action.templatePresentation.text)
        assertEquals(selectedIcon, action.templatePresentation.icon)
    }

    fun testUpdateUsesSelectedAgentIcon() {
        val selectedIcon = AllIcons.Actions.Execute
        val action = AgentComboBoxAction(listOf(agentItem("a", "Agent A", selectedIcon)))
        val component = action.createCustomComponent(action.templatePresentation, "test")
        val group = popupGroup(action, component)
        val childAction = group.childActionsOrStubs.single() as AnAction
        childAction.actionPerformed(
            AnActionEvent.createFromAnAction(
                childAction,
                null,
                ActionPlaces.UNKNOWN,
                SimpleDataContext.EMPTY_CONTEXT
            )
        )

        val event = AnActionEvent.createFromAnAction(
            action,
            null,
            ActionPlaces.UNKNOWN,
            SimpleDataContext.EMPTY_CONTEXT
        )
        action.update(event)

        assertEquals("Agent A", event.presentation.text)
        assertEquals(selectedIcon, event.presentation.icon)
    }

    fun testUpdateAgentsPreservesSelectedAgentIcon() {
        val originalIcon = AllIcons.Actions.Execute
        val refreshedIcon = AllIcons.Actions.Suspend
        val action = AgentComboBoxAction(listOf(agentItem("a", "Agent A", originalIcon)))
        val component = action.createCustomComponent(action.templatePresentation, "test")
        val group = popupGroup(action, component)
        val childAction = group.childActionsOrStubs.single() as AnAction
        childAction.actionPerformed(
            AnActionEvent.createFromAnAction(
                childAction,
                null,
                ActionPlaces.UNKNOWN,
                SimpleDataContext.EMPTY_CONTEXT
            )
        )

        action.updateAgents(listOf(agentItem("a", "Agent A", refreshedIcon)))
        val button = component as ComboBoxAction.ComboBoxButton
        val event = AnActionEvent.createFromAnAction(
            action,
            null,
            ActionPlaces.UNKNOWN,
            SimpleDataContext.EMPTY_CONTEXT
        )
        action.update(event)

        assertEquals(refreshedIcon, action.getSelectedAgent()?.icon)
        assertEquals(refreshedIcon, action.templatePresentation.icon)
        assertEquals(refreshedIcon, button.icon)
        assertEquals(refreshedIcon, event.presentation.icon)
    }

    private fun popupGroup(action: AgentComboBoxAction, component: JComponent): DefaultActionGroup {
        val method = ComboBoxAction::class.java.getDeclaredMethod(
            "createPopupActionGroup",
            JComponent::class.java,
            com.intellij.openapi.actionSystem.DataContext::class.java
        ).apply {
            isAccessible = true
        }
        return method.invoke(action, component, SimpleDataContext.EMPTY_CONTEXT) as DefaultActionGroup
    }

    private fun agentItem(id: String, displayName: String, icon: Icon) = AgentComboBoxAction.AgentItem(
        id = id,
        displayName = displayName,
        description = "Description",
        icon = icon,
        agentDefinition = AgentRegistry.InstalledAgent(
            registryAgentId = id,
            id = id,
            displayName = displayName,
            description = "Description",
            version = "1.0.0",
            iconPath = null,
            installMethod = github.ponyhuang.acpplugin.services.InstallMethod.NPX,
            sourceLabel = "Official",
            command = "npx",
            args = emptyList(),
            env = emptyMap(),
            isLegacy = false,
        )
    )
}
