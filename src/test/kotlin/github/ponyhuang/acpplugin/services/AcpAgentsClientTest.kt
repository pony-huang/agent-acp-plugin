package github.ponyhuang.acpplugin.services

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.MethodName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpAgentsClientTest {

    @Test
    fun testSanitizeIncomingJsonRpcMessageDowngradesUsageUpdateWhenUsedIsNull() {
        val message = JsonRpcNotification(
            method = MethodName("session/update"),
            params = JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("session-1"),
                    "update" to JsonObject(
                        mapOf(
                            "sessionUpdate" to JsonPrimitive("usage_update"),
                            "used" to JsonNull,
                            "size" to JsonPrimitive(200000),
                            "cost" to JsonObject(
                                mapOf(
                                    "amount" to JsonPrimitive(0.244519),
                                    "currency" to JsonPrimitive("USD")
                                )
                            )
                        )
                    )
                )
            )
        )

        val sanitized = sanitizeIncomingJsonRpcMessage(message) as JsonRpcNotification
        val update = (sanitized.params as JsonObject)["update"] as JsonObject

        assertEquals("usage_update_invalid_null_used", (update["sessionUpdate"] as JsonPrimitive).content)
        assertEquals(JsonNull, update["used"])
    }

    @Test
    fun testSanitizeIncomingJsonRpcMessageLeavesValidUsageUpdateUntouched() {
        val message = JsonRpcNotification(
            method = MethodName("session/update"),
            params = JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("session-1"),
                    "update" to JsonObject(
                        mapOf(
                            "sessionUpdate" to JsonPrimitive("usage_update"),
                            "used" to JsonPrimitive(120),
                            "size" to JsonPrimitive(200000)
                        )
                    )
                )
            )
        )

        val sanitized = sanitizeIncomingJsonRpcMessage(message)

        assertSame(message, sanitized)
    }

    @Test
    fun testSanitizeIncomingJsonRpcMessageLeavesNonSessionUpdateNotificationsUntouched() {
        val message: JsonRpcMessage = JsonRpcNotification(
            method = MethodName("other/method"),
            params = JsonObject(mapOf("value" to JsonPrimitive("ok")))
        )

        val sanitized = sanitizeIncomingJsonRpcMessage(message)

        assertSame(message, sanitized)
        assertTrue(sanitized is JsonRpcNotification)
    }
}
