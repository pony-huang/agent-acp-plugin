package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.UUID

/**
 * Telemetry service for tracking user events and errors.
 * Inspired by acp-ui's telemetry.ts using Application Insights pattern.
 *
 * Note: This is a basic implementation that logs to console and can be extended
 * to support actual analytics backends like Application Insights, Mixpanel, etc.
 */
@Service(Service.Level.PROJECT)
class AcpTelemetryService : Disposable {

    // Telemetry enabled state
    private var isEnabled: Boolean = true

    // Machine/User ID for tracking
    private val machineId: String = generateMachineId()

    // Event buffer for batching (optional future use)
    private val eventBuffer = mutableListOf<TelemetryEvent>()
    private val maxBufferSize = 50

    /**
     * Initialize telemetry service.
     * @param enabled Whether telemetry is enabled (respects user preference)
     */
    fun init(enabled: Boolean = true) {
        isEnabled = enabled
        if (!enabled) {
            println("[Telemetry] Disabled by user preference")
            return
        }
        println("[Telemetry] Initialized (machine: ${machineId.take(8)}...)")
        trackEvent("AppLaunch", mapOf("version" to getPluginVersion()))
    }

    /**
     * Track a custom event.
     */
    fun trackEvent(name: String, properties: Map<String, String> = emptyMap()) {
        if (!isEnabled) return

        val event = TelemetryEvent(
            name = name,
            properties = properties + mapOf(
                "machineId" to machineId,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )

        // Log to console
        println("[Telemetry] Event: $name ${formatProperties(properties)}")

        // Add to buffer
        eventBuffer.add(event)
        if (eventBuffer.size >= maxBufferSize) {
            flush()
        }
    }

    /**
     * Track an exception/error.
     */
    fun trackError(error: Throwable, properties: Map<String, String> = emptyMap()) {
        if (!isEnabled) return

        val errorProperties: Map<String, String> = mapOf(
            "errorType" to (error::class.simpleName ?: "Unknown"),
            "errorMessage" to (error.message ?: "No message"),
            "stackTrace" to error.stackTraceToString().take(500)
        ) + properties

        println("[Telemetry] Error: ${error::class.simpleName}: ${error.message}")
        trackEvent("Error", errorProperties)
    }

    /**
     * Track a metric value.
     */
    fun trackMetric(name: String, value: Double, properties: Map<String, String> = emptyMap()) {
        if (!isEnabled) return

        val metricProperties = properties + mapOf(
            "value" to value.toString()
        )

        println("[Telemetry] Metric: $name = $value")
        trackEvent("Metric_$name", metricProperties)
    }

    /**
     * Track session created event.
     */
    fun trackSessionCreated(agentName: String, success: Boolean) {
        trackEvent(
            "SessionCreated",
            mapOf(
                "agentName" to agentName,
                "success" to success.toString()
            )
        )
    }

    /**
     * Track session resumed event.
     */
    fun trackSessionResumed(agentName: String, success: Boolean) {
        trackEvent(
            "SessionResumed",
            mapOf(
                "agentName" to agentName,
                "success" to success.toString()
            )
        )
    }

    /**
     * Track session disconnected event.
     */
    fun trackSessionDisconnected(
        agentName: String,
        sessionDurationSeconds: Long,
        messageCount: Int
    ) {
        trackEvent(
            "SessionDisconnected",
            mapOf(
                "agentName" to agentName,
                "sessionDurationSeconds" to sessionDurationSeconds.toString(),
                "messageCount" to messageCount.toString()
            )
        )
    }

    /**
     * Track prompt sent event.
     */
    fun trackPromptSent(messageLength: Int, stopReason: String) {
        trackEvent(
            "PromptSent",
            mapOf(
                "messageLength" to messageLength.toString(),
                "stopReason" to stopReason
            )
        )
    }

    /**
     * Set whether telemetry is enabled.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            // Flush any pending data before disabling
            flush()
        }
        println("[Telemetry] ${if (enabled) "Enabled" else "Disabled"}")
    }

    /**
     * Check if telemetry is enabled.
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Flush any buffered events.
     */
    fun flush() {
        if (eventBuffer.isEmpty()) return

        println("[Telemetry] Flushing ${eventBuffer.size} buffered events")
        // In a full implementation, this would send to an analytics backend
        eventBuffer.clear()
    }

    /**
     * Get current machine ID.
     */
    fun getMachineId(): String = machineId

    private fun generateMachineId(): String {
        // Generate a unique machine ID based on various system properties
        val sb = StringBuilder()
        sb.append(System.getProperty("user.name", "unknown"))
        sb.append("-")
        sb.append(System.getProperty("os.name", "unknown"))
        sb.append("-")
        sb.append(System.getProperty("os.version", "unknown"))
        sb.append("-")
        sb.append(System.getProperty("user.dir", "unknown").hashCode())
        return UUID.nameUUIDFromBytes(sb.toString().toByteArray()).toString()
    }

    private fun getPluginVersion(): String {
        return try {
            val pluginDescription = java.util.Properties()
            val stream = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
            stream?.close()
            "1.0.0" // Default version, can be read from plugin.xml if needed
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun formatProperties(properties: Map<String, String>): String {
        if (properties.isEmpty()) return ""
        return properties.entries.joinToString(", ", " {", "}") { "${it.key}=${it.value}" }
    }

    override fun dispose() {
        flush()
    }

    /**
     * Telemetry event data class.
     */
    data class TelemetryEvent(
        val name: String,
        val properties: Map<String, String>,
        val timestamp: Long = System.currentTimeMillis()
    )
}