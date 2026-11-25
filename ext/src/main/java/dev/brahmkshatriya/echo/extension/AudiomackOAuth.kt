package dev.brahmkshatriya.echo.extension

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth 1.0a request signer matching the scheme used by the Audiomack Android client
 * (signpost with HMAC-SHA1, consumer "audiomack-android").
 *
 * The consumer key/secret are the public application credentials embedded in the official
 * client. They are not user credentials. The per-user `oauth_token`/`oauth_token_secret`
 * returned by `POST access_token` are kept in [AudiomackSessionManager] and never logged.
 */
object AudiomackOAuth {
    // Public application credentials from the official client's signpost consumer (see docs).
    const val DEFAULT_CONSUMER_KEY = "audiomack-android"
    const val DEFAULT_CONSUMER_SECRET = "051ecef67795633034e15cf94cd54872"

    const val USER_AGENT = "Audiomack/8.11.4 (Android)"
    const val API_BASE = "https://api.audiomack.com/v1/"

    /**
     * Builds an OAuth Authorization header for a request.
     *
     * @param url absolute URL without query string (the signature base uses this)
     * @param params all query parameters and, for form posts, all form fields
     */
    fun authorizationHeader(
        method: String,
        url: String,
        params: Map<String, String> = emptyMap(),
        consumerKey: String = DEFAULT_CONSUMER_KEY,
        consumerSecret: String = DEFAULT_CONSUMER_SECRET,
        token: String? = null,
        tokenSecret: String? = null
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")

        val oauthParams = linkedMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0"
        )
        if (!token.isNullOrBlank()) oauthParams["oauth_token"] = token

        val allParams = LinkedHashMap<String, String>()
        oauthParams.forEach { (key, value) -> allParams[key] = value }
        params.forEach { (key, value) -> allParams[key] = value }

        val parameterString = allParams.entries
            .sortedWith(compareBy({ percentEncode(it.key) }, { percentEncode(it.value) }))
            .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

        val signatureBase = listOf(
            method.uppercase(),
            percentEncode(url),
            percentEncode(parameterString)
        ).joinToString("&")

        val signingKey = "${percentEncode(consumerSecret)}&${percentEncode(tokenSecret.orEmpty())}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        val signature = Base64.getEncoder()
            .encodeToString(mac.doFinal(signatureBase.toByteArray(StandardCharsets.UTF_8)))

        oauthParams["oauth_signature"] = signature

        return "OAuth " + oauthParams.entries
            .sortedBy { it.key }
            .joinToString(", ") { "${it.key}=\"${percentEncode(it.value)}\"" }
    }

    /** RFC 3986 percent encoding as required by OAuth 1.0a. */
    fun percentEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    /** Form encoding used for `application/x-www-form-urlencoded` request bodies. */
    fun formEncode(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, StandardCharsets.UTF_8.name())}=" +
                URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())
        }
}
