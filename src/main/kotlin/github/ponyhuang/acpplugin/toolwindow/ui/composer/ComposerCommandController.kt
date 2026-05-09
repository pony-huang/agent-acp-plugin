package github.ponyhuang.acpplugin.toolwindow.ui.composer

data class ComposerCommandItem(
    val name: String,
    val description: String,
    val hint: String? = null
)

internal class ComposerCommandController {
    private var availableCommands: List<ComposerCommandItem> = emptyList()

    fun updateCommands(commands: List<ComposerCommandItem>) {
        availableCommands = commands
    }

    fun hasCommands(): Boolean = availableCommands.isNotEmpty()

    fun shouldShowSuggestions(text: String, isBusy: Boolean): Boolean {
        if (availableCommands.isEmpty() || isBusy) {
            return false
        }

        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) {
            return false
        }

        return !trimmed.contains(' ')
    }

    fun filterCommands(text: String): List<ComposerCommandItem> {
        val filter = text.trimStart().removePrefix("/").lowercase()
        if (filter.isBlank()) {
            return availableCommands
        }

        return availableCommands.filter { command ->
            command.name.lowercase().startsWith(filter) ||
                command.description.lowercase().contains(filter)
        }
    }

    fun commandText(command: ComposerCommandItem): String = "/${command.name} "
}
