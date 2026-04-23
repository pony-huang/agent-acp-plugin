package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Service for persisting ACP sessions to disk.
 * Inspired by acp-ui's session store using @tauri-apps/plugin-store.
 */
@Service(Service.Level.PROJECT)
class AcpSessionPersistenceService(private val project: Project) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    // In-memory cache of saved sessions
    private val _savedSessions = MutableStateFlow<List<SavedSession>>(emptyList())
    val savedSessions: StateFlow<List<SavedSession>> = _savedSessions.asStateFlow()
    private val logger: Logger = Logger.getInstance(AcpSessionPersistenceService::class.java)

    // Session storage file path
    private val sessionFilePath: Path by lazy {
        val configDir = getConfigDirectory()
        configDir.resolve("sessions.json")
    }

    /**
     * Saved session model matching acp-ui's SavedSession interface.
     */
    @Serializable
    data class SavedSession(
        val id: String,
        val agentName: String,
        val sessionId: String,
        val title: String,
        val lastUpdated: Long,
        val cwd: String,
        val supportsLoadSession: Boolean = true
    )

    private fun getConfigDirectory(): Path {
        val configPath = when {
            // IDE config directory (preferred for persistence)
            System.getProperty("idea.config.path") != null -> {
                Paths.get(System.getProperty("idea.config.path"), "ACPChat")
            }
            // Fallback to project directory
            project.basePath != null -> {
                Paths.get(project.basePath, ".acpchat")
            }
            // Last resort: temp directory
            else -> {
                Paths.get(System.getProperty("java.io.tmpdir"), "ACPChat", project.name)
            }
        }

        if (!configPath.exists()) {
            configPath.createDirectories()
        }
        return configPath
    }

    /**
     * Initialize - load sessions from disk.
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessions = loadSessionsFromDisk()
                _savedSessions.value = sessions
            }
        }
    }

    /**
     * Add a new session to persistence.
     */
    suspend fun addSession(session: SavedSession) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessions = _savedSessions.value.toMutableList()
                // Remove existing session with same sessionId (if any)
                sessions.removeAll { it.sessionId == session.sessionId && it.agentName == session.agentName }
                sessions.add(session)
                saveSessionsToDisk(sessions)
                _savedSessions.value = sessions
            }
        }
    }

    /**
     * Update an existing session.
     */
    suspend fun updateSession(sessionId: String, agentName: String, update: (SavedSession) -> SavedSession) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessions = _savedSessions.value.toMutableList()
                val index = sessions.indexOfFirst { it.sessionId == sessionId && it.agentName == agentName }
                if (index >= 0) {
                    sessions[index] = update(sessions[index])
                    saveSessionsToDisk(sessions)
                    _savedSessions.value = sessions
                }
            }
        }
    }

    /**
     * Delete a session by ID.
     */
    suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sessions = _savedSessions.value.toMutableList()
                sessions.removeAll { it.sessionId == sessionId }
                saveSessionsToDisk(sessions)
                _savedSessions.value = sessions
            }
        }
    }

    /**
     * Get sessions for a specific agent.
     */
    fun getSessionsForAgent(agentName: String): List<SavedSession> {
        return _savedSessions.value
            .filter { it.agentName == agentName && it.supportsLoadSession }
            .sortedByDescending { it.lastUpdated }
    }

    /**
     * Get all resumable sessions.
     */
    fun getResumableSessions(): List<SavedSession> {
        return _savedSessions.value
            .filter { it.supportsLoadSession }
            .sortedByDescending { it.lastUpdated }
    }

    private fun loadSessionsFromDisk(): List<SavedSession> {
        val file = sessionFilePath.toFile()
        if (!file.exists()) {
            return emptyList()
        }

        return try {
            val content = file.readText()
            if (content.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<List<SavedSession>>(content)
            }
        } catch (e: Exception) {
            logger.info("[AcpSessionPersistenceService] Failed to load sessions: ${e.message}")
            emptyList()
        }
    }

    private fun saveSessionsToDisk(sessions: List<SavedSession>) {
        try {
            val content = json.encodeToString(sessions)
            sessionFilePath.writeText(content)
            logger.info("[AcpSessionPersistenceService] Saved ${sessions.size} sessions to $sessionFilePath")
        } catch (e: Exception) {
            logger.info("[AcpSessionPersistenceService] Failed to save sessions: ${e.message}")
        }
    }

    /**
     * Get the session file path for display.
     */
    fun getSessionFilePath(): String = sessionFilePath.toString()

    /**
     * Clear all saved sessions.
     */
    suspend fun clearAllSessions() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                saveSessionsToDisk(emptyList())
                _savedSessions.value = emptyList()
            }
        }
    }
}