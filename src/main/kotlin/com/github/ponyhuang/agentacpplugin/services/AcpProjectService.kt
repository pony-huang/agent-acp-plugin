package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.TestOnly

@Service(Service.Level.PROJECT)
class AcpProjectService(
    val project: Project,
) : Disposable {

    private val projectScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("AcpProjectService:${project.name}")
    )
    private val registryMutex = Mutex()
    private val agentServices = LinkedHashMap<String, AcpAgentService>()

    private var agentServiceFactory: AcpAgentServiceFactory = DefaultAcpAgentServiceFactory
    private var runtimeConnector: AcpRuntimeConnector = ProcessAcpRuntimeConnector

    @Volatile
    private var disposed = false

    suspend fun getOrCreateAgentService(descriptor: AcpAgentDescriptor): AcpAgentService = registryMutex.withLock {
        ensureUsable()
        val existing = agentServices[descriptor.id]
        if (existing != null) {
            require(existing.descriptor == descriptor) {
                "ACP agent '${descriptor.id}' is already registered with a different configuration. Remove it before recreating."
            }
            return existing
        }
        return agentServiceFactory.create(project, descriptor, projectScope, runtimeConnector).also {
            agentServices[descriptor.id] = it
        }
    }

    suspend fun removeAgentService(agentId: String, reason: String? = null) {
        val service = registryMutex.withLock {
            agentServices.remove(agentId)
        } ?: return

        runCatching {
            service.disconnect(reason ?: "Removed from project registry")
        }
        service.dispose()
    }

    suspend fun connect(descriptor: AcpAgentDescriptor): AcpConnectionState.Connected {
        return getOrCreateAgentService(descriptor).connect()
    }

    suspend fun getAgentService(agentId: String): AcpAgentService? = registryMutex.withLock {
        agentServices[agentId]
    }

    suspend fun listAgentServices(): List<AcpAgentService> = registryMutex.withLock {
        agentServices.values.toList()
    }

    override fun dispose() {
        disposed = true
        runBlocking {
            val services = registryMutex.withLock {
                agentServices.values.toList().also { agentServices.clear() }
            }
            services.forEach { service ->
                runCatching {
                    service.disconnect("Project service disposed")
                }
                service.dispose()
            }
        }
        projectScope.cancel()
    }

    @TestOnly
    internal fun replaceAgentServiceFactoryForTests(factory: AcpAgentServiceFactory) {
        agentServiceFactory = factory
    }

    @TestOnly
    internal fun replaceRuntimeConnectorForTests(connector: AcpRuntimeConnector) {
        runtimeConnector = connector
    }

    private fun ensureUsable() {
        check(!disposed) { "ACP project service is already disposed" }
    }
}
