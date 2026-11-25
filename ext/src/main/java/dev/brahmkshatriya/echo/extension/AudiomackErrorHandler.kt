package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ClientException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** A real API/network failure. Never converted into an empty success by the caller. */
class AudiomackException(
    val statusCode: Int?,
    val apiCode: String?,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Maps HTTP/network failures to actionable errors. Echo displays [AudiomackException.message];
 * 401/403 are converted to [ClientException] so the host app can log the user out correctly.
 */
object AudiomackErrorHandler {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun fromResponse(
        statusCode: Int,
        body: String,
        retryAfterSeconds: Long?,
        isAuthenticated: Boolean,
        userId: String?
    ): Exception {
        val parsed = parseError(body)
        val message = when {
            statusCode == 401 && isAuthenticated && userId != null -> return ClientException.Unauthorized(userId)
            statusCode == 401 -> return ClientException.LoginRequired()
            statusCode == 403 -> parsed ?: "Audiomack denied access to this content (HTTP 403)."
            statusCode == 404 -> parsed ?: "Audiomack could not find this content (HTTP 404)."
            statusCode == 429 -> parsed
                ?: "Audiomack rate limit reached. Please wait and try again."
            statusCode in 500..599 -> parsed
                ?: "Audiomack is temporarily unavailable (HTTP $statusCode). Please try again."
            statusCode == 422 -> parsed ?: "Audiomack rejected the request (HTTP 422)."
            else -> parsed ?: "Audiomack request failed (HTTP $statusCode)."
        }
        return AudiomackException(
            statusCode = statusCode,
            apiCode = parseCode(body),
            message = message,
            retryAfterSeconds = retryAfterSeconds
        )
    }

    fun network(e: IOException): AudiomackException = AudiomackException(
        statusCode = null,
        apiCode = null,
        message = "Could not reach Audiomack. Check your internet connection and try again.",
        cause = e
    )

    fun invalidResponse(message: String): AudiomackException = AudiomackException(
        statusCode = null,
        apiCode = null,
        message = message
    )

    private fun parseError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val description = obj.stringOrNull("description")
            val message = obj.stringOrNull("message")
            val errors = obj["errors"]
            val joined = (description ?: message)?.trim()
            val firstError = when (errors) {
                is kotlinx.serialization.json.JsonArray -> errors.mapNotNull {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.takeIf { text -> text.isNotBlank() && text != "null" }
                }.joinToString("; ").ifBlank { null }
                is kotlinx.serialization.json.JsonObject -> errors.values.mapNotNull {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.takeIf { text -> text.isNotBlank() && text != "null" }
                }.joinToString("; ").ifBlank { null }
                else -> null
            }
            when {
                !joined.isNullOrBlank() && !firstError.isNullOrBlank() && joined != firstError ->
                    "$joined ($firstError)"
                !joined.isNullOrBlank() -> joined
                !firstError.isNullOrBlank() -> firstError
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCode(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject.stringOrNull("errorcode")
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
}
