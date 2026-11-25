package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Signed client for the Audiomack v1 API used by the official Android app.
 *
 * Every request is OAuth 1.0a signed. Anonymous catalog access works with the public app
 * consumer; account endpoints require the user token pair obtained through
 * [login]. Failures are surfaced as [AudiomackException] / [ClientException], never as empty
 * successful results.
 */
class AudiomackApiClient(
    private val sessions: AudiomackSessionManager,
    private val consumerKey: () -> String = { AudiomackOAuth.DEFAULT_CONSUMER_KEY },
    private val consumerSecret: () -> String = { AudiomackOAuth.DEFAULT_CONSUMER_SECRET },
    private val httpClient: OkHttpClient = defaultClient()
) {
    val client: OkHttpClient get() = httpClient

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ---------------------------------------------------------------- transport

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        authRequired: Boolean = false,
        anonymous: Boolean = false,
        retryOnRateLimit: Boolean = true
    ): JsonElement {
        val session = if (anonymous) null else sessions.load()
        if (authRequired && session == null) {
            throw dev.brahmkshatriya.echo.common.helpers.ClientException.LoginRequired()
        }

        val baseBuilder = AudiomackOAuth.API_BASE.toHttpUrlOrNull()?.newBuilder()
            ?: throw AudiomackErrorHandler.invalidResponse("Invalid Audiomack API base URL.")
        path.split('/').filter { it.isNotBlank() }.forEach { baseBuilder.addPathSegment(it) }
        val unsignedUrl = baseBuilder.build()

        val params = LinkedHashMap<String, String>()
        query.forEach { (k, v) -> params[k] = v }
        form?.forEach { (k, v) -> params[k] = v }

        val header = AudiomackOAuth.authorizationHeader(
            method = method,
            url = unsignedUrl.toString(),
            params = params,
            consumerKey = consumerKey(),
            consumerSecret = consumerSecret(),
            token = session?.token,
            tokenSecret = session?.tokenSecret
        )

        val urlBuilder = unsignedUrl.newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val url = urlBuilder.build()

        val body = if (form != null) {
            FormBody.Builder().apply {
                form.forEach { (k, v) -> add(k, v) }
            }.build()
        } else null

        val request = Request.Builder()
            .url(url)
            .header("Authorization", header)
            .header("User-Agent", AudiomackOAuth.USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .header("Referer", "https://audiomack.com/")
            .apply {
                when (method.uppercase()) {
                    "GET" -> get()
                    "POST" -> post(body ?: FormBody.Builder().build())
                    "PUT" -> put(body ?: FormBody.Builder().build())
                    "PATCH" -> patch(body ?: FormBody.Builder().build())
                    "DELETE" -> if (body != null) delete(body) else delete()
                    else -> method(method, body)
                }
            }
            .build()

        val response = try {
            httpClient.newCall(request).await()
        } catch (e: ClientException) {
            throw e
        } catch (e: IOException) {
            throw AudiomackErrorHandler.network(e)
        }

        val responseBody = try {
            response.body?.string().orEmpty()
        } catch (e: IOException) {
            throw AudiomackErrorHandler.network(e)
        }

        if (!response.isSuccessful) {
            if (retryOnRateLimit && (response.code == 429 || response.code == 503)) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    ?.coerceIn(1L, 5L) ?: 1L
                delay(retryAfter * 1000)
                return execute(
                    method, path, query, form, authRequired, anonymous,
                    retryOnRateLimit = false
                )
            }
            // A stored token can be rejected for public endpoints; retry once anonymously so
            // discovery keeps working while the host app decides to re-authenticate.
            if (response.code == 401 && session != null && !authRequired) {
                return execute(
                    method, path, query, form, authRequired = false, anonymous = true,
                    retryOnRateLimit = false
                )
            }
            throw AudiomackErrorHandler.fromResponse(
                statusCode = response.code,
                body = responseBody,
                retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                isAuthenticated = session != null,
                userId = session?.userId
            )
        }

        if (responseBody.isBlank()) return JsonNull
        return try {
            json.parseToJsonElement(responseBody)
        } catch (e: Exception) {
            throw AudiomackErrorHandler.invalidResponse(
                "Audiomack returned an unreadable response. Please try again."
            )
        }
    }

    private suspend fun getObject(
        path: String,
        query: Map<String, String> = emptyMap(),
        authRequired: Boolean = false,
        anonymous: Boolean = false
    ): JsonObject? {
        val element = execute("GET", path, query, authRequired = authRequired, anonymous = anonymous)
        return element.asObject()
    }

    private suspend fun resultsArray(path: String, query: Map<String, String> = emptyMap()): List<JsonObject> {
        val element = execute("GET", path, query)
        if (element is JsonArray) return element.objects()
        val obj = element.asObject() ?: return emptyList()
        return obj["results"].objects()
    }

    // ---------------------------------------------------------------- authentication

    /**
     * Legitimate OAuth 1.0a password exchange used by the official app:
     * `POST /v1/access_token` with `x_auth_mode=client_auth`.
     * Verified against the live service: unknown email returns HTTP 422, not 404.
     */
    suspend fun login(email: String, password: String): AudiomackSessionManager.Session {
        if (email.isBlank() || password.isBlank()) {
            throw AudiomackException(null, null, "Enter your Audiomack email and password.")
        }
        val form = mapOf(
            "x_auth_mode" to "client_auth",
            "x_auth_username" to email.trim(),
            "x_auth_password" to password
        )
        val element = execute("POST", "access_token", form = form, anonymous = true)
        val obj = element.asObject()
            ?: throw AudiomackErrorHandler.invalidResponse(
                "Audiomack did not return a session. Check your credentials and try again."
            )
        val token = obj.string("oauth_token")
        val secret = obj.string("oauth_token_secret")
        val user = obj["user"].asObject()
        if (token.isBlank() || secret.isBlank() || user == null) {
            throw AudiomackErrorHandler.invalidResponse(
                "Audiomack returned an incomplete session. Please try again."
            )
        }
        val expiration = obj.string("oauth_token_expiration").toLongOrNull()
            ?: obj["oauth_token_expiration"]?.jsonPrimitive?.content?.toLongOrNull()
        return AudiomackSessionManager.Session(
            token = token,
            tokenSecret = secret,
            expiresAtEpochSeconds = normalizeExpiration(expiration),
            userId = user.string("id").ifBlank { user.string("screen_name") },
            urlSlug = user.string("url_slug"),
            name = user.string("screen_name").ifBlank { user.string("name") },
            coverUrl = user.stringOrNull("image")
        )
    }

    /** Revokes the current access token server-side. Best effort; local session is cleared by caller. */
    suspend fun logout() {
        try {
            execute("DELETE", "access_token", authRequired = true)
        } catch (e: AudiomackException) {
            // A token that is already invalid is effectively logged out.
            if (e.statusCode != 401 && e.statusCode != 403) throw e
        }
    }

    suspend fun getAccount(): JsonObject? {
        val element = execute("GET", "user", authRequired = true)
        return element.asObject()?.get("results").asObject() ?: element.asObject()
    }

    // ---------------------------------------------------------------- catalog & discovery

    suspend fun getTrending(page: Int = 1): List<JsonObject> =
        resultsArray("music/trending", pageQuery(page))

    suspend fun getRecent(page: Int = 1): List<JsonObject> =
        resultsArray("music/recent", pageQuery(page))

    suspend fun getGenreTrending(genre: String, page: Int = 1): List<JsonObject> =
        resultsArray("music/$genre/trending", pageQuery(page))

    suspend fun getPlaylistCategories(): List<JsonObject> {
        val obj = getObject("playlist/categories", mapOf("featured" to "yes", "limit" to "999"))
        return obj?.get("results").asObject()?.get("categories").objects()
            ?: obj?.get("categories").objects()
            ?: emptyList()
    }

    suspend fun getPlaylistsForCategory(slug: String, page: Int = 1): List<JsonObject> {
        val obj = getObject("playlist/categories", mapOf("slug" to slug, "page" to page.toString()))
        obj?.get("results").asObject()?.let { results ->
            return results["playlists"].objects().ifEmpty { results["rows"].objects() }
        }
        return obj?.get("results").objects().ifEmpty { obj?.get("playlists").objects() }
    }

    // ---------------------------------------------------------------- search

    suspend fun search(query: String, show: String = "songs", page: Int = 1): List<JsonObject> {
        if (query.isBlank()) return emptyList()
        return resultsArray(
            "search",
            mapOf("q" to query, "show" to show, "page" to page.toString())
        )
    }

    suspend fun liveSearch(query: String, limit: Int = 10): JsonObject? {
        if (query.isBlank()) return null
        return getObject("livesearch", mapOf("q" to query, "limit" to limit.toString()))
    }

    // ---------------------------------------------------------------- media details

    suspend fun getTrack(trackId: String, artistSlug: String? = null, urlSlug: String? = null): JsonObject? {
        if (!artistSlug.isNullOrBlank() && !urlSlug.isNullOrBlank()) {
            val bySlug = try {
                getObject("music/song/$artistSlug/$urlSlug")
            } catch (e: AudiomackException) {
                if (e.statusCode == 404) null else throw e
            }
            bySlug?.resultsOrSelf()?.let { return it }
        }
        // Numeric detail lookup is the canonical fallback when a public slug is stale.
        return getObject("music/$trackId")?.resultsOrSelf()
    }

    suspend fun getAlbum(
        albumId: String,
        artistSlug: String? = null,
        urlSlug: String? = null
    ): JsonObject? {
        if (!artistSlug.isNullOrBlank() && !urlSlug.isNullOrBlank()) {
            val bySlug = try {
                getObject("music/album/$artistSlug/$urlSlug")
            } catch (e: AudiomackException) {
                if (e.statusCode == 404) null else throw e
            }
            bySlug?.resultsOrSelf()?.let { return it }
        }
        if (!albumId.all { it.isDigit() }) return null
        return getObject("music/$albumId")?.resultsOrSelf()
    }

    suspend fun getPlaylist(
        playlistId: String,
        artistSlug: String? = null,
        urlSlug: String? = null
    ): JsonObject? {
        if (!artistSlug.isNullOrBlank() && !urlSlug.isNullOrBlank()) {
            val bySlug = try {
                getObject("playlist/$artistSlug/$urlSlug")
            } catch (e: AudiomackException) {
                if (e.statusCode == 404) null else throw e
            }
            bySlug?.resultsOrSelf()?.let { return it }
        }
        if (!playlistId.all { it.isDigit() }) return null
        return getObject("playlist/$playlistId")?.resultsOrSelf()
    }

    suspend fun getArtist(slugOrId: String): JsonObject? {
        val obj = if (slugOrId.all { it.isDigit() }) {
            getObject("artist", mapOf("id" to slugOrId))
        } else {
            getObject("artist/$slugOrId")
        } ?: return null
        return obj.resultsOrSelf()
    }

    suspend fun getArtistUploads(slug: String, page: Int = 1): List<JsonObject> =
        resultsArray("artist/$slug/uploads", mapOf("page" to page.toString()))

    suspend fun getArtistPlaylists(slug: String, page: Int = 1): List<JsonObject> =
        resultsArray("artist/$slug/playlists", mapOf("page" to page.toString()))

    suspend fun getArtistFavorites(slug: String, show: String, page: Int = 1): List<JsonObject> =
        resultsArray("artist/$slug/favorites", mapOf("show" to show, "page" to page.toString()))

    suspend fun getArtistFollowing(slug: String, pagingToken: String? = null): List<JsonObject> =
        resultsArray(
            "artist/$slug/following",
            pagingToken?.let { mapOf("paging_token" to it) } ?: emptyMap()
        )

    suspend fun getArtistPinned(slug: String): List<JsonObject> {
        val obj = getObject("artist/$slug/pinned") ?: return emptyList()
        return obj["results"].objects().ifEmpty { obj["results"].asObject()?.values?.flatMap { it.objects() }.orEmpty() }
    }

    suspend fun getRelatedTracks(musicId: String): List<JsonObject> =
        resultsArray(
            "recommendations/related-songs",
            mapOf("musicId" to musicId, "source" to "song")
        )

    suspend fun getRelatedArtists(artistId: String): List<JsonObject> =
        resultsArray("recommendations/related-artists/$artistId")

    suspend fun getMyPlaylists(page: Int = 1, limit: Int = 50): List<JsonObject> =
        resultsArray(
            "user/playlists",
            mapOf("page" to page.toString(), "limit" to limit.toString(), "exclude_tracks" to "true")
        )

    suspend fun getListenHistory(pagingToken: String? = null): List<JsonObject> {
        val obj = getObject(
            "user/listen-history",
            (pagingToken?.let { mapOf("paging_token" to it, "limit" to "50") }
                ?: mapOf("limit" to "50")),
            authRequired = true
        ) ?: return emptyList()
        return obj["results"].objects().ifEmpty { obj.resultsOrSelf()["results"].objects() }
    }

    // ---------------------------------------------------------------- playback

    /**
     * Resolves the authorized full-length stream. Verified live: returns a CloudFront signed
     * `music.audiomack.com` m4a with HTTP Range support (full track, not a preview).
     */
    suspend fun getStreamUrl(trackId: String, hq: Boolean): String {
        val query = if (hq) mapOf("hq" to "1") else emptyMap()
        val obj = getObject("music/play/$trackId", query)
            ?: throw AudiomackException(null, null, "Audiomack did not return a stream for this track.")
        val signed = obj.string("signedUrl").ifBlank { obj.string("url") }
        if (signed.isBlank()) {
            throw AudiomackException(
                null, null,
                "Audiomack did not authorize a playable stream for this track."
            )
        }
        return signed
    }

    // ---------------------------------------------------------------- library mutations

    suspend fun setMusicFavorite(itemId: String, albumId: String?, shouldFavorite: Boolean) {
        val fields = linkedMapOf(
            "album_id" to albumId.orEmpty(),
            "section" to "library",
            "source_tab" to "library",
            "button" to "favorite",
            "offline" to "false",
            "vend_id" to "",
            "app_sess_id" to "",
            "carrier" to "",
            "on_wifi" to "true",
            "app_lang" to "en"
        )
        if (shouldFavorite) {
            execute("PUT", "music/$itemId/favorite", form = fields, authRequired = true)
        } else {
            execute("DELETE", "music/$itemId/favorite", form = fields, authRequired = true)
        }
    }

    suspend fun setPlaylistFavorite(itemId: String, shouldFavorite: Boolean) {
        val fields = linkedMapOf(
            "playlist_id" to itemId,
            "section" to "library",
            "source_tab" to "library",
            "button" to "favorite",
            "offline" to "false",
            "vend_id" to "",
            "app_sess_id" to "",
            "carrier" to "",
            "on_wifi" to "true",
            "app_lang" to "en"
        )
        if (shouldFavorite) {
            execute("PUT", "playlist/$itemId/favorite", form = fields, authRequired = true)
        } else {
            execute("DELETE", "playlist/$itemId/favorite", form = fields, authRequired = true)
        }
    }

    suspend fun setFollowing(artistSlug: String, shouldFollow: Boolean) {
        if (shouldFollow) {
            execute("PUT", "artist/$artistSlug/follow", authRequired = true)
        } else {
            execute("DELETE", "artist/$artistSlug/follow", authRequired = true)
        }
    }

    suspend fun createPlaylist(
        title: String,
        description: String?,
        genre: String = "other"
    ): JsonObject {
        val form = linkedMapOf(
            "title" to title,
            "genre" to genre,
            "description" to description.orEmpty(),
            "private" to "no",
            "section" to "library",
            "music_id" to "",
            "image" to "",
            "image_banner" to ""
        )
        val element = execute("POST", "playlist", form = form, authRequired = true)
        return element.asObject()?.resultsOrSelf() ?: JsonObject(emptyMap())
    }

    /**
     * Live-verified against 8.11.4: the update route reads query parameters, not a form body,
     * and [genre] must be one of the app's lowercase genre slugs (for example `punjabi`).
     */
    suspend fun editPlaylist(
        playlistId: String,
        title: String,
        description: String?,
        isPrivate: Boolean,
        genre: String = "other"
    ) {
        val query = linkedMapOf(
            "title" to title,
            "genre" to genre,
            "description" to description.orEmpty(),
            "private" to if (isPrivate) "yes" else "no",
            "music_id" to ""
        )
        execute("PUT", "playlist/$playlistId", query = query, authRequired = true)
    }

    suspend fun deletePlaylist(playlistId: String) {
        execute("DELETE", "playlist/$playlistId", authRequired = true)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val form = linkedMapOf(
            "music_id" to trackId,
            "album_id" to "",
            "playlist_id" to playlistId,
            "section" to "library",
            "source_tab" to "library",
            "button" to "add",
            "offline" to "false",
            "vend_id" to "",
            "app_sess_id" to "",
            "carrier" to "",
            "on_wifi" to "true",
            "app_lang" to "en"
        )
        execute("POST", "playlist/$playlistId/track", form = form, authRequired = true)
    }

    suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val form = linkedMapOf(
            "album_id" to "",
            "playlist_id" to playlistId,
            "section" to "library",
            "source_tab" to "library",
            "button" to "remove",
            "offline" to "false",
            "vend_id" to "",
            "app_sess_id" to "",
            "carrier" to "",
            "on_wifi" to "true",
            "app_lang" to "en"
        )
        execute(
            "DELETE",
            "playlist/$playlistId/track/${trackIds.joinToString(",")}",
            form = form,
            authRequired = true
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun pageQuery(page: Int): Map<String, String> =
        if (page <= 1) emptyMap() else mapOf("page" to page.toString())

    private fun normalizeExpiration(value: Long?): Long? {
        if (value == null || value <= 0) return null
        return if (value > 10_000_000_000L) value / 1000 else value
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.objects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

internal fun JsonObject.resultsOrSelf(): JsonObject =
    (this["results"] as? JsonObject) ?: this

internal fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.content?.takeIf { it != "null" } ?: ""

internal fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

internal fun JsonObject.longOrNull(key: String): Long? =
    this[key]?.jsonPrimitive?.content?.trim()?.toLongOrNull()

internal fun JsonObject.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

internal fun JsonObject.boolOrNull(key: String): Boolean? = when (this[key]?.jsonPrimitive?.content) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> null
}

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.arrayOrEmpty(): JsonArray =
    this as? JsonArray ?: JsonArray(emptyList())
