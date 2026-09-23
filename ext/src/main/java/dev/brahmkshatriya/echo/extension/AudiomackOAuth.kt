package dev.brahmkshatriya.echo.extension

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AudiomackOAuth {
    const val DEFAULT_CONSUMER_KEY = "audiomack-js"
    const val DEFAULT_CONSUMER_SECRET = "f3ac5b086f3eab260520d8e3049561e6"

    fun generateAuthorizationHeader(
        method: String,
        url: String,
        queryParams: Map<String, String> = emptyMap(),
        consumerKey: String = DEFAULT_CONSUMER_KEY,
        consumerSecret: String = DEFAULT_CONSUMER_SECRET
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")

        val oauthParams = mutableMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0"
        )

        val allParams = mutableMapOf<String, String>()
        allParams.putAll(oauthParams)
        allParams.putAll(queryParams)

        val sortedParamString = allParams.entries
            .sortedBy { it.key }
            .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

        val signatureBaseString = listOf(
            method.uppercase(),
            percentEncode(url),
            percentEncode(sortedParamString)
        ).joinToString("&")

        val signingKey = "${percentEncode(consumerSecret)}&"
        val keySpec = SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(keySpec)
        val rawSignature = mac.doFinal(signatureBaseString.toByteArray(StandardCharsets.UTF_8))
        val signature = Base64.getEncoder().encodeToString(rawSignature)

        oauthParams["oauth_signature"] = signature

        val headerValues = oauthParams.entries
            .sortedBy { it.key }
            .joinToString(", ") { "${it.key}=\"${percentEncode(it.value)}\"" }

        return "OAuth $headerValues"
    }

    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }
}
