package com.github.ponyhuang.agentacpplugin.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.readText

class DefaultClientSessionOperationsTest {

    @Test
    fun testFsWriteTextFileCreatesMissingParentDirectories() {
        val tempDir = Files.createTempDirectory("default-client-session-ops")
        try {
            val targetFile = tempDir.resolve("nested/path/PermissionRequestInfo.kt")
            val operations = DefaultClientSessionOperations(
                sessionUpdateSink = {},
            )

            val response = runBlocking {
                operations.fsWriteTextFile(targetFile.toString(), "package test", null)
            }

            assertTrue(response.javaClass.simpleName == "WriteTextFileResponse")
            assertTrue(Files.exists(targetFile))
            assertEquals("package test", targetFile.readText())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
