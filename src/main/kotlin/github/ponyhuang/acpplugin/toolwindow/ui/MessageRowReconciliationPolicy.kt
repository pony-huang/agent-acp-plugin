package github.ponyhuang.acpplugin.toolwindow.ui

internal class MessageRowReconciliationPolicy {
    fun decide(
        previousMessageIds: List<String>,
        nextMessageIds: List<String>,
        showEmptyState: Boolean,
        hadEmptyState: Boolean,
        replacedExistingRow: Boolean
    ): MessageRowReconciliationPlan {
        val canKeepChildOrder =
            !showEmptyState &&
                !hadEmptyState &&
                !replacedExistingRow &&
                previousMessageIds == nextMessageIds
        val canAppendOnly =
            !showEmptyState &&
                !hadEmptyState &&
                !replacedExistingRow &&
                nextMessageIds.size > previousMessageIds.size &&
                nextMessageIds.subList(0, previousMessageIds.size) == previousMessageIds

        val mutation = when {
            showEmptyState -> MessageRowChildMutation.Rebuild(emptyList(), showEmptyState = true)
            canKeepChildOrder -> MessageRowChildMutation.Keep
            canAppendOnly -> MessageRowChildMutation.Append(
                appendedMessageIds = nextMessageIds.drop(previousMessageIds.size),
                startRow = previousMessageIds.size
            )
            else -> MessageRowChildMutation.Rebuild(nextMessageIds, showEmptyState = false)
        }

        return MessageRowReconciliationPlan(
            staleMessageIds = previousMessageIds.filterNot(nextMessageIds::contains),
            mutation = mutation
        )
    }
}

internal data class MessageRowReconciliationPlan(
    val staleMessageIds: List<String>,
    val mutation: MessageRowChildMutation
)

internal sealed interface MessageRowChildMutation {
    data object Keep : MessageRowChildMutation

    data class Append(
        val appendedMessageIds: List<String>,
        val startRow: Int
    ) : MessageRowChildMutation

    data class Rebuild(
        val orderedMessageIds: List<String>,
        val showEmptyState: Boolean
    ) : MessageRowChildMutation
}
