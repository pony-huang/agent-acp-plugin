package com.github.ponyhuang.agentacpplugin.services.render

import java.time.Instant

enum class TimelineItemType(val appendable: Boolean) {
    USER_MESSAGE(false),
    THOUGHT(true),
    TOOL_CALL(true),
    TOOL_RESULT(true),
    FINAL_MESSAGE(true),
    STATUS(false),
}

enum class TimelineDisplayState {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

data class TimelineItem(
    val itemId: String,
    val turnId: String,
    val itemType: TimelineItemType,
    val displayState: TimelineDisplayState,
    val sequenceNumber: Int,
    val title: String? = null,
    val textContent: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)
