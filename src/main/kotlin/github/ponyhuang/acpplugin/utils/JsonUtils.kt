package github.ponyhuang.acpplugin.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object JsonUtils {
    @PublishedApi
    internal val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun <T> encodeToJson(value: T, serializer: kotlinx.serialization.KSerializer<T>): String {
        return json.encodeToString(serializer, value)
    }

    inline fun <reified T> decodeFromJson(jsonString: String): T {
        return json.decodeFromString(serializer<T>(), jsonString)
    }
}
