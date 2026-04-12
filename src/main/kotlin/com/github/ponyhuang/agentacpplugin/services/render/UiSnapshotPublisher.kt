package com.github.ponyhuang.agentacpplugin.services.render

import java.util.concurrent.CopyOnWriteArrayList

class UiSnapshotPublisher {
    private val listeners = CopyOnWriteArrayList<(Map<String, SessionViewSnapshot>) -> Unit>()
    private var latest: Map<String, SessionViewSnapshot> = emptyMap()

    fun publish(snapshots: Map<String, SessionViewSnapshot>) {
        latest = snapshots
        listeners.forEach { listener -> listener(latest) }
    }

    fun addListener(listener: (Map<String, SessionViewSnapshot>) -> Unit): () -> Unit {
        listeners += listener
        listener(latest)
        return { listeners -= listener }
    }

    fun latest(): Map<String, SessionViewSnapshot> = latest
}
