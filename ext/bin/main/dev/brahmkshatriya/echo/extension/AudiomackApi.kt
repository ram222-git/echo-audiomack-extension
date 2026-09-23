package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class AudiomackApi(
    private val consumerKeyProvider: () -> String = { AudiomackOAuth.DEFAULT_CONSUMER_KEY },
    private val consumerSecretProvider: () -> String = { AudiomackOAuth.DEFAULT_CONSUMER_SECRET }
) {
    val client: OkHttpClient = OkHttpClient()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private suspend fun getJson(endpoint: String, queryParams: Map<String, String> = emptyMap()): JsonObject? {
        val baseUrl = "https://api.audiomack.com/v1/$endpoint"
        val httpUrlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return null
        queryParams.forEach { (k, v) ->
            httpUrlBuilder.addQueryParameter(k, v)
        }
        val requestUrl = httpUrlBuilder.build().toString()

        val authHeader = AudiomackOAuth.generateAuthorizationHeader(
            method = "GET",
            url = baseUrl,
            queryParams = queryParams,
            consumerKey = consumerKeyProvider(),
            consumerSecret = consumerSecretProvider()
        )

        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", authHeader)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        return try {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getRscItems(urlOrPath: String): List<JsonObject> {
        val url = if (urlOrPath.startsWith("http")) urlOrPath else "https://audiomack.com/$urlOrPath"
        val request = Request.Builder()
            .url(url)
            .header("RSC", "1")
            .header("Accept", "text/x-component")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        return try {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            parseRscBody(body)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseRscBody(body: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        val seenIds = mutableSetOf<String>()

        fun extractItems(element: JsonElement) {
            when (element) {
                is JsonArray -> {
                    val first = element.firstOrNull()
                    if (first is JsonObject && first.containsKey("id") && (first.containsKey("title") || first.containsKey("name"))) {
                        for (item in element) {
                            if (item is JsonObject) {
                                val id = item["id"]?.jsonPrimitive?.content
                                if (!id.isNullOrBlank() && seenIds.add(id)) {
                                    results.add(item)
                                }
                            }
                        }
                        return
                    }
                    for (item in element) {
                        extractItems(item)
                    }
                }
                is JsonObject -> {
                    for (value in element.values) {
                        extractItems(value)
                    }
                }
                else -> {}
            }
        }

        body.lineSequence().forEach { line ->
            val colonIndex = line.indexOf(':')
            if (colonIndex != -1 && colonIndex < line.length - 1) {
                val jsonPart = line.substring(colonIndex + 1).trim()
                if ((jsonPart.startsWith("{") && jsonPart.endsWith("}")) ||
                    (jsonPart.startsWith("[") && jsonPart.endsWith("]"))) {
                    try {
                        val parsed = json.parseToJsonElement(jsonPart)
                        extractItems(parsed)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return results
    }

    // RSC Home Feed Endpoints (with fallback to OAuth REST API)
    suspend fun getDiscoverTrending(): List<Track> {
        val items = getRscItems("https://audiomack.com/trending-now/songs?_rsc=9h31m")
        if (items.isNotEmpty()) {
            return items.map { parseTrack(it) }
        }
        return getTrending(1)
    }

    suspend fun getDiscoverArtists(): List<Artist> {
        val items = getRscItems("https://audiomack.com/artists/for-you?_rsc=9h31m")
        if (items.isNotEmpty()) {
            return items.map { parseArtist(it) }
        }
        return searchArtists("popular", 1)
    }

    suspend fun getDiscoverRecent(): List<Track> {
        val items = getRscItems("https://audiomack.com/recent?_rsc=9h31m")
        if (items.isNotEmpty()) {
            return items.map { parseTrack(it) }
        }
        return getRecent(1)
    }

    suspend fun getChartSongs(): List<Track> {
        val items = getRscItems("https://audiomack.com/top/songs?_rsc=ipj3q")
        if (items.isNotEmpty()) {
            return items.map { parseTrack(it) }
        }
        return getTrending(1)
    }

    suspend fun getChartAlbums(): List<Album> {
        val items = getRscItems("https://audiomack.com/top/albums?_rsc=ipj3q")
        if (items.isNotEmpty()) {
            return items.map { parseAlbum(it) }
        }
        return searchAlbums("top", 1)
    }

    suspend fun getChartPlaylists(): List<Playlist> {
        val items = getRscItems("https://audiomack.com/top/playlists?_rsc=ipj3q")
        if (items.isNotEmpty()) {
            return items.map { parsePlaylist(it) }
        }
        return getPlaylists(1)
    }

    suspend fun getTrending(page: Int = 1): List<Track> {
        val params = if (page > 1) mapOf("page" to page.toString()) else emptyMap()
        val json = getJson("music/trending", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parseTrack(it) }
    }

    suspend fun getRecent(page: Int = 1): List<Track> {
        val params = if (page > 1) mapOf("page" to page.toString()) else emptyMap()
        val json = getJson("music/recent", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parseTrack(it) }
    }

    suspend fun getPlaylists(page: Int = 1): List<Playlist> {
        val params = if (page > 1) mapOf("page" to page.toString()) else emptyMap()
        val json = getJson("playlists", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parsePlaylist(it) }
    }

    suspend fun searchSongs(query: String, page: Int = 1): List<Track> {
        val params = mutableMapOf("q" to query, "show" to "songs")
        if (page > 1) params["page"] = page.toString()
        val json = getJson("search", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parseTrack(it) }
    }

    suspend fun searchAlbums(query: String, page: Int = 1): List<Album> {
        val params = mutableMapOf("q" to query, "show" to "albums")
        if (page > 1) params["page"] = page.toString()
        val json = getJson("search", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parseAlbum(it) }
    }

    suspend fun searchArtists(query: String, page: Int = 1): List<Artist> {
        val params = mutableMapOf("q" to query, "show" to "artists")
        if (page > 1) params["page"] = page.toString()
        val json = getJson("search", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parseArtist(it) }
    }

    suspend fun searchAll(query: String, page: Int = 1): List<EchoMediaItem> {
        val params = mutableMapOf("q" to query, "show" to "music")
        if (page > 1) params["page"] = page.toString()
        val json = getJson("search", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.mapNotNull {
            val type = it["type"]?.jsonPrimitive?.content ?: "song"
            when (type) {
                "album" -> parseAlbum(it)
                "artist" -> parseArtist(it)
                "playlist" -> parsePlaylist(it)
                else -> parseTrack(it)
            }
        }
    }

    suspend fun getTrackDetail(trackId: String): Track? {
        val json = getJson("music/$trackId") ?: return null
        val results = json["results"]?.jsonObject ?: return null
        return parseTrack(results)
    }

    suspend fun getStreamUrl(
        trackId: String,
        artistSlug: String? = null,
        urlSlug: String? = null
    ): String? {
        // Build list of candidate play URLs to try (slug format first, then numeric ID)
        val candidates = mutableListOf<String>()
        if (!artistSlug.isNullOrBlank() && !urlSlug.isNullOrBlank()) {
            candidates.add("https://api.audiomack.com/v1/music/play/$artistSlug/$urlSlug")
        }
        candidates.add("https://api.audiomack.com/v1/music/play/$trackId")

        val queryParams = mapOf(
            "environment" to "desktop-web",
            "hq" to "true"
        )

        for (baseUrl in candidates) {
            val result = tryPlayUrl(baseUrl, queryParams)
            if (!result.isNullOrBlank()) return result
        }

        // Last resort: re-fetch track detail and grab streaming_url from JSON
        return getStreamingUrlFromDetail(artistSlug, urlSlug, trackId)
    }

    private suspend fun tryPlayUrl(baseUrl: String, queryParams: Map<String, String>): String? {
        val httpUrlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return null
        queryParams.forEach { (k, v) -> httpUrlBuilder.addQueryParameter(k, v) }
        val requestUrl = httpUrlBuilder.build().toString()

        val authHeader = AudiomackOAuth.generateAuthorizationHeader(
            method = "GET",
            url = baseUrl,
            queryParams = queryParams,
            consumerKey = consumerKeyProvider(),
            consumerSecret = consumerSecretProvider()
        )

        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", authHeader)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "https://audiomack.com/")
            .header("Origin", "https://audiomack.com")
            .build()

        return try {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) return null

            // The play endpoint issues a redirect to the audio file URL.
            // After OkHttpClient follows it, the final URL is the stream URL.
            val finalUrl = response.request.url.toString()
            if (finalUrl != requestUrl && (finalUrl.contains("audiomack.com") || finalUrl.contains(".m4a") || finalUrl.contains(".mp3") || finalUrl.contains(".aac"))) {
                return finalUrl
            }

            // Fallback: try parsing body as JSON (in case API behaviour changes)
            val body = response.body?.string() ?: return null
            try {
                val jsonObject = json.parseToJsonElement(body).jsonObject
                jsonObject["signedUrl"]?.jsonPrimitive?.content
                    ?: jsonObject["url"]?.jsonPrimitive?.content
                    ?: jsonObject["stream_url"]?.jsonPrimitive?.content
                    ?: jsonObject["hls_url"]?.jsonPrimitive?.content
                    ?: jsonObject["results"]?.jsonObject?.get("signedUrl")?.jsonPrimitive?.content
                    ?: jsonObject["results"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: jsonObject["results"]?.jsonObject?.get("stream_url")?.jsonPrimitive?.content
                    ?: jsonObject["results"]?.jsonObject?.get("hls_url")?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }


    private suspend fun getStreamingUrlFromDetail(
        artistSlug: String?,
        urlSlug: String?,
        trackId: String
    ): String? {
        // Try slug-based detail endpoint first
        val endpoint = if (!artistSlug.isNullOrBlank() && !urlSlug.isNullOrBlank())
            "music/$artistSlug/$urlSlug"
        else
            "music/$trackId"

        val detailJson = getJson(endpoint) ?: getJson("music/$trackId") ?: return null
        val results = detailJson["results"]?.jsonObject ?: return null
        return results["streaming_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }


    fun getPreviewRequest(trackId: String): Pair<String, Map<String, String>> {
        val url = "https://api.audiomack.com/v1/music/preview/$trackId"
        val authHeader = AudiomackOAuth.generateAuthorizationHeader(
            method = "GET",
            url = url,
            queryParams = emptyMap(),
            consumerKey = consumerKeyProvider(),
            consumerSecret = consumerSecretProvider()
        )
        return Pair(url, mapOf("Authorization" to authHeader, "User-Agent" to "Mozilla/5.0"))
    }

    fun parseTrack(json: JsonObject): Track {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Title"
        val artistName = when (val a = json["artist"]) {
            is JsonObject -> a["name"]?.jsonPrimitive?.content
            else -> a?.jsonPrimitive?.content
        } ?: json["uploader"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: "Unknown Artist"
        val artistSlug = json["uploader"]?.jsonObject?.get("url_slug")?.jsonPrimitive?.content ?: ""
        val uploaderId = json["uploader"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: artistSlug

        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content

        val durationSec = json["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: json["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        val durationMs = if (durationSec > 0) durationSec * 1000 else null

        val streamingUrl = json["streaming_url"]?.jsonPrimitive?.content

        val albumTitle = json["album"]?.jsonPrimitive?.content
        val album = if (!albumTitle.isNullOrBlank()) {
            Album(id = albumTitle, title = albumTitle)
        } else null

        val isExplicit = json["explicit"]?.jsonPrimitive?.content.equals("yes", ignoreCase = true)
        val genre = json["genre"]?.jsonPrimitive?.content ?: ""

        val extras = mutableMapOf<String, String>()
        if (!streamingUrl.isNullOrBlank()) extras["streaming_url"] = streamingUrl
        val songSlug = json["url_slug"]?.jsonPrimitive?.content
        if (!songSlug.isNullOrBlank()) extras["url_slug"] = songSlug
        if (artistSlug.isNotBlank()) extras["artist_slug"] = artistSlug

        val streamables = listOf(
            Streamable.server(
                id = id,
                quality = 128,
                title = "Audiomack Audio",
                extras = extras
            )
        )

        return Track(
            id = id,
            title = title,
            artists = listOf(Artist(id = uploaderId, name = artistName)),
            album = album,
            cover = coverUrl?.toImageHolder(),
            duration = durationMs,
            isExplicit = isExplicit,
            genres = if (genre.isNotBlank()) listOf(genre) else emptyList(),
            extras = extras,
            streamables = streamables
        )
    }

    fun parseAlbum(json: JsonObject): Album {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Album"
        val artistName = when (val a = json["artist"]) {
            is JsonObject -> a["name"]?.jsonPrimitive?.content
            else -> a?.jsonPrimitive?.content
        } ?: json["uploader"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: "Unknown Artist"
        val uploaderId = json["uploader"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content

        return Album(
            id = id,
            title = title,
            artists = listOf(Artist(id = uploaderId, name = artistName)),
            cover = coverUrl?.toImageHolder()
        )
    }

    fun parseArtist(json: JsonObject): Artist {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val name = json["name"]?.jsonPrimitive?.content
            ?: json["artist"]?.jsonPrimitive?.content
            ?: "Unknown Artist"
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content

        return Artist(
            id = id,
            name = name,
            cover = coverUrl?.toImageHolder()
        )
    }

    fun parsePlaylist(json: JsonObject): Playlist {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Playlist"
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content
        val trackCount = json["track_count"]?.jsonPrimitive?.content?.toLongOrNull()

        return Playlist(
            id = id,
            title = title,
            isEditable = false,
            cover = coverUrl?.toImageHolder(),
            trackCount = trackCount,
            isPrivate = false
        )
    }
}
