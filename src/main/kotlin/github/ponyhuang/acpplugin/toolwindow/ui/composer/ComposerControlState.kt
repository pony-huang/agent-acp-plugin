package github.ponyhuang.acpplugin.toolwindow.ui.composer

import github.ponyhuang.acpplugin.MyBundle
import com.intellij.icons.AllIcons
import javax.swing.Icon

internal data class ComposerSelectorState(
    val agentEnabled: Boolean,
    val planEnabled: Boolean,
    val modelEnabled: Boolean
) {
    companion object {
        fun from(
            isSessionConnected: Boolean,
            isBusy: Boolean,
            hasSelectedPlan: Boolean,
            hasSelectedModel: Boolean
        ): ComposerSelectorState {
            return ComposerSelectorState(
                agentEnabled = !isBusy,
                planEnabled = isSessionConnected && !isBusy && hasSelectedPlan,
                modelEnabled = isSessionConnected && !isBusy && hasSelectedModel
            )
        }
    }
}

internal data class ComposerControlPresentation(
    val sendEnabled: Boolean,
    val connectionVisible: Boolean,
    val connectionEnabled: Boolean,
    val connectionText: String,
    val connectionIcon: Icon,
    val connectionTooltip: String
)

internal object ComposerControlStatePresenter {
    fun present(
        isSessionConnected: Boolean,
        isBusy: Boolean,
        hasSelectedAgent: Boolean
    ): ComposerControlPresentation {
        val canInterrupt = isSessionConnected && isBusy
        return ComposerControlPresentation(
            sendEnabled = isSessionConnected && !isBusy,
            connectionVisible = false,
            connectionEnabled = canInterrupt || !isBusy && (isSessionConnected || hasSelectedAgent),
            connectionText = when {
                canInterrupt -> MyBundle.message("input.interrupt")
                isSessionConnected -> MyBundle.message("input.disconnect")
                else -> MyBundle.message("input.connect")
            },
            connectionIcon = if (isSessionConnected) AllIcons.Actions.Suspend else AllIcons.Actions.Execute,
            connectionTooltip = when {
                canInterrupt -> MyBundle.message("input.tooltipInterrupt")
                isSessionConnected -> MyBundle.message("input.tooltipDisconnect")
                else -> MyBundle.message("input.tooltipConnect")
            }
        )
    }
}
