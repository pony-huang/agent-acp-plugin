package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.services.AcpSessionService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JRadioButton

class PermissionRequestCardPanelTest {

    @Test
    fun updatesSubmittedStateInPlace() {
        val panel = instantiatePermissionCard(
            AcpSessionService.PermissionRequestInfo(
                requestId = "request-1",
                toolCallId = "tool-1",
                title = "Run command",
                options = listOf(
                    AcpSessionService.PermissionOptionInfo(
                        optionId = "allow-once",
                        label = "Allow once",
                        kind = "allow_once"
                    ),
                    AcpSessionService.PermissionOptionInfo(
                        optionId = "reject-once",
                        label = "Reject once",
                        kind = "reject_once"
                    )
                ),
                selectedOptionId = null,
                submitted = false
            )
        )

        panel.updateRequest(
            AcpSessionService.PermissionRequestInfo(
                requestId = "request-1",
                toolCallId = "tool-1",
                title = "Run command",
                options = listOf(
                    AcpSessionService.PermissionOptionInfo(
                        optionId = "allow-once",
                        label = "Allow once",
                        kind = "allow_once"
                    ),
                    AcpSessionService.PermissionOptionInfo(
                        optionId = "reject-once",
                        label = "Reject once",
                        kind = "reject_once"
                    )
                ),
                selectedOptionId = "reject-once",
                submitted = true
            )
        )

        val radios = findAllByType(panel, JRadioButton::class.java)
        val button = findAllByType(panel, JButton::class.java).single()

        assertEquals(2, radios.size)
        assertTrue(radios[1].isSelected)
        assertFalse(radios[0].isEnabled)
        assertFalse(radios[1].isEnabled)
        assertEquals("Submitted", button.text)
        assertFalse(button.isEnabled)
    }

    private fun instantiatePermissionCard(
        request: AcpSessionService.PermissionRequestInfo
    ): PermissionRequestCardPanel {
        return PermissionRequestCardPanel(request) { }
    }

    private fun <T : Component> findAllByType(root: Component, type: Class<T>): List<T> {
        val results = mutableListOf<T>()
        if (type.isInstance(root)) {
            results += type.cast(root)
        }
        if (root is Container) {
            root.components.forEach { child ->
                results += findAllByType(child, type)
            }
        }
        return results
    }
}
