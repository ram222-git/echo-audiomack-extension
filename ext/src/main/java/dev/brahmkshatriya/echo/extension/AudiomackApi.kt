package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
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

    internal suspend fun getJson(endpoint: String, queryParams: Map<String, String> = emptyMap()): JsonObject? {
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

    internal suspend fun getRscItems(urlOrPath: String): List<JsonObject> {
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

    suspend fun searchPlaylists(query: String, page: Int = 1): List<Playlist> {
        val params = mutableMapOf("q" to query, "show" to "playlists")
        if (page > 1) params["page"] = page.toString()
        val json = getJson("search", params) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parsePlaylist(it) }
    }

    suspend fun searchRsc(query: String, show: String = "music"): List<EchoMediaItem> {
        return getRscSearch(query, show)
    }

    suspend fun getRscSearch(query: String, show: String = "music"): List<EchoMediaItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val items = getRscItems("https://audiomack.com/search?q=$encodedQuery&show=$show&_rsc=ramnz")
        return items.mapNotNull {
            val type = it["type"]?.jsonPrimitive?.content ?: "song"
            when (type) {
                "album" -> parseAlbum(it)
                "artist" -> parseArtist(it)
                "playlist" -> parsePlaylist(it)
                else -> parseTrack(it)
            }
        }
    }

    suspend fun getLiveSearch(query: String, limit: Int = 10): JsonObject? {
        return getJson("livesearch", mapOf("q" to query, "limit" to limit.toString()))
    }

    suspend fun getQuickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val json = getLiveSearch(query.trim(), limit = 10) ?: return emptyList()
        val results = json["results"]?.jsonArray ?: return emptyList()

        val items = mutableListOf<QuickSearchItem>()
        val seenQueries = mutableSetOf<String>()
        val seenMedia = mutableSetOf<String>()

        for (elem in results) {
            val obj = elem as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.content ?: "song"

            // Query suggestion (artist name or song/album title)
            val title = (if (type == "artist") obj["name"]?.jsonPrimitive?.content else obj["title"]?.jsonPrimitive?.content)?.trim()
            if (!title.isNullOrBlank() && seenQueries.add(title.lowercase())) {
                items.add(QuickSearchItem.Query(query = title, searched = false))
            }

            // Direct media suggestion
            val media: EchoMediaItem = when (type) {
                "artist" -> parseArtist(obj)
                "album" -> parseAlbum(obj)
                "playlist" -> parsePlaylist(obj)
                else -> parseTrack(obj)
            }
            if (media.id.isNotBlank() && seenMedia.add("${type}_${media.id}")) {
                items.add(QuickSearchItem.Media(media = media, searched = false))
            }
        }
        return items
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

    fun parseArtists(
        artistString: String?,
        uploaderJson: JsonObject? = null,
        title: String? = null
    ): List<Artist> {
        val uploaderName = uploaderJson?.get("name")?.jsonPrimitive?.content?.trim()
        val uploaderSlug = uploaderJson?.get("url_slug")?.jsonPrimitive?.content?.trim().orEmpty()
        val uploaderId = uploaderJson?.get("id")?.jsonPrimitive?.content?.trim() ?: uploaderSlug

        val rawArtistString = artistString?.trim().orEmpty()
        val baseString = if (rawArtistString.isNotBlank() && !rawArtistString.equals("Unknown Artist", ignoreCase = true)) {
            rawArtistString
        } else {
            uploaderName.orEmpty()
        }

        if (baseString.isBlank()) {
            return listOf(Artist(id = uploaderSlug.ifBlank { uploaderId.ifBlank { "unknown" } }, name = "Unknown Artist"))
        }

        val splitRegex = Regex("""(?i)\s*(?:,\s*|\s*&\s*|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+[xX]\s+|\s*/\s*|\s*\\\s*|\s*\|\s*|\s+with\s+|;\s*)\s*""")
        val rawParts = baseString.split(splitRegex).map { it.trim() }.filter { it.isNotBlank() }

        val names = mutableListOf<String>()
        names.addAll(rawParts)

        if (!title.isNullOrBlank()) {
            val featRegex = Regex("""(?i)[\(\[](?:feat\.?|ft\.?|featuring)\s+([^\]\)]+)[\)\]]""")
            val featMatch = featRegex.find(title)
            if (featMatch != null) {
                val featStr = featMatch.groupValues[1]
                val featParts = featStr.split(splitRegex).map { it.trim() }.filter { it.isNotBlank() }
                for (part in featParts) {
                    if (names.none { it.equals(part, ignoreCase = true) }) {
                        names.add(part)
                    }
                }
            }
        }

        val distinctNames = names.distinctBy { it.lowercase() }
        if (distinctNames.isEmpty()) {
            return listOf(Artist(id = uploaderSlug.ifBlank { uploaderId.ifBlank { "unknown" } }, name = baseString))
        }

        return distinctNames.mapIndexed { index, name ->
            val isUploader = !uploaderName.isNullOrBlank() && name.equals(uploaderName, ignoreCase = true)
            val slug = if (isUploader && uploaderSlug.isNotBlank()) {
                uploaderSlug
            } else if (index == 0 && uploaderSlug.isNotBlank() && (distinctNames.size == 1 || uploaderName.isNullOrBlank())) {
                uploaderSlug
            } else {
                name.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            }

            val extras = mutableMapOf<String, String>()
            if (slug.isNotBlank()) {
                extras["url_slug"] = slug
                extras["artist_slug"] = slug
            }
            if (isUploader) {
                extras["from_uploader"] = "true"
            }

            Artist(
                id = if (isUploader && uploaderId.isNotBlank()) uploaderId else slug.ifBlank { name },
                name = name,
                extras = extras
            )
        }
    }

    fun parseTrack(json: JsonObject): Track {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Title"
        val artistName = when (val a = json["artist"]) {
            is JsonObject -> a["name"]?.jsonPrimitive?.content
            else -> a?.jsonPrimitive?.content
        } ?: json["uploader"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: "Unknown Artist"
        val uploaderObj = json["uploader"] as? JsonObject
        val artists = parseArtists(artistName, uploaderObj, title)
        val mainArtist = artists.firstOrNull()
        val artistSlug = mainArtist?.extras?.get("url_slug")
            ?: uploaderObj?.get("url_slug")?.jsonPrimitive?.content ?: ""

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
            artists = artists,
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
        val uploaderObj = json["uploader"] as? JsonObject
        val artists = parseArtists(artistName, uploaderObj, title)
        val mainArtist = artists.firstOrNull()
        val uploaderId = uploaderObj?.get("id")?.jsonPrimitive?.content ?: ""
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content
        val trackCount = json["track_count"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: json["tracks"]?.jsonArray?.size?.toLong()
        val durationSec = json["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val durationMs = if (durationSec > 0) durationSec * 1000 else null
        val description = json["description"]?.jsonPrimitive?.content

        val urlSlug = json["url_slug"]?.jsonPrimitive?.content ?: ""
        val artistSlug = mainArtist?.extras?.get("url_slug")
            ?: uploaderObj?.get("url_slug")?.jsonPrimitive?.content
            ?: json["uploader_url_slug"]?.jsonPrimitive?.content ?: ""
        val extras = mutableMapOf<String, String>()
        if (urlSlug.isNotBlank()) extras["url_slug"] = urlSlug
        if (artistSlug.isNotBlank()) extras["artist_slug"] = artistSlug

        return Album(
            id = id,
            title = title,
            artists = artists,
            cover = coverUrl?.toImageHolder(),
            trackCount = trackCount,
            duration = durationMs,
            description = description,
            extras = extras
        )
    }

    fun parseArtist(json: JsonObject): Artist {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val name = json["name"]?.jsonPrimitive?.content
            ?: json["artist"]?.jsonPrimitive?.content
            ?: "Unknown Artist"
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content
        val bannerUrl = json["image_banner"]?.jsonPrimitive?.content
        val bio = json["bio"]?.jsonPrimitive?.content
        val urlSlug = json["url_slug"]?.jsonPrimitive?.content ?: ""

        val verified = json["verified"]?.jsonPrimitive?.content
        val isVerified = !verified.isNullOrBlank() && !verified.equals("null", ignoreCase = true)
        val followers = json["followers_count"]?.jsonPrimitive?.content
        val plays = (json["stats"] as? JsonObject)?.get("plays")?.jsonPrimitive?.content
        val subtitle = listOfNotNull(
            if (isVerified) "Verified Artist" else null,
            if (!followers.isNullOrBlank()) "$followers Followers" else null,
            if (!plays.isNullOrBlank()) "$plays Plays" else null
        ).joinToString(" • ").ifBlank {
            if (urlSlug.isNotBlank()) "@$urlSlug" else null
        }

        val extras = mutableMapOf<String, String>()
        if (urlSlug.isNotBlank()) {
            extras["url_slug"] = urlSlug
            extras["artist_slug"] = urlSlug
        }
        if (id.isNotBlank()) extras["id"] = id

        return Artist(
            id = urlSlug.ifBlank { id },
            name = name,
            cover = coverUrl?.toImageHolder(),
            background = (bannerUrl ?: coverUrl)?.toImageHolder(),
            bio = bio,
            subtitle = subtitle,
            extras = extras
        )
    }

    fun parsePlaylist(json: JsonObject): Playlist {
        val id = json["id"]?.jsonPrimitive?.content
            ?: json["playlistId"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Playlist"
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content
        val trackCount = json["track_count"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: json["tracks"]?.jsonArray?.size?.toLong()
        val durationSec = json["tracks_total_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val durationMs = if (durationSec > 0) durationSec * 1000 else null

        val selfLink = json["links"]?.jsonObject?.get("self")?.jsonPrimitive?.content
        val (linkArtist, linkSlug) = if (!selfLink.isNullOrBlank()) extractArtistAndSlug(selfLink) else Pair(null, null)

        val authorName = when (val a = json["artist"]) {
            is JsonObject -> a["name"]?.jsonPrimitive?.content
            else -> a?.jsonPrimitive?.content
        } ?: json["uploader"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: "Audiomack"
        val authorSlug = json["uploader"]?.jsonObject?.get("url_slug")?.jsonPrimitive?.content
            ?: json["uploader_url_slug"]?.jsonPrimitive?.content
            ?: (json["artist"] as? JsonObject)?.get("url_slug")?.jsonPrimitive?.content
            ?: linkArtist ?: ""
        val urlSlug = json["url_slug"]?.jsonPrimitive?.content ?: linkSlug ?: ""
        val description = json["description"]?.jsonPrimitive?.content

        val extras = mutableMapOf<String, String>()
        if (urlSlug.isNotBlank()) extras["url_slug"] = urlSlug
        if (authorSlug.isNotBlank()) extras["artist_slug"] = authorSlug

        return Playlist(
            id = id,
            title = title,
            isEditable = false,
            cover = coverUrl?.toImageHolder(),
            authors = listOf(Artist(id = authorSlug.ifBlank { id }, name = authorName)),
            trackCount = trackCount,
            duration = durationMs,
            description = description,
            isPrivate = false,
            extras = extras
        )
    }

    fun parseAlbumTrack(json: JsonObject, albumTitle: String?, albumCover: String?): Track {
        val id = json["song_id"]?.jsonPrimitive?.content
            ?: json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Track"
        val artistName = json["artist"]?.jsonPrimitive?.content
            ?: json["uploader"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: "Unknown Artist"
        val uploaderObj = json["uploader"] as? JsonObject
        val artists = parseArtists(artistName, uploaderObj, title)
        val mainArtist = artists.firstOrNull()
        val artistSlug = mainArtist?.extras?.get("url_slug")
            ?: json["uploader_url_slug"]?.jsonPrimitive?.content
            ?: uploaderObj?.get("url_slug")?.jsonPrimitive?.content ?: ""
        val songSlug = json["url_slug"]?.jsonPrimitive?.content ?: ""
        val coverUrl = json["image"]?.jsonPrimitive?.content
            ?: json["image_base"]?.jsonPrimitive?.content
            ?: albumCover

        val durationSec = json["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val durationMs = if (durationSec > 0) durationSec * 1000 else null

        val streamingUrl = json["streaming_url"]?.jsonPrimitive?.content

        val extras = mutableMapOf<String, String>()
        if (!streamingUrl.isNullOrBlank()) extras["streaming_url"] = streamingUrl
        if (songSlug.isNotBlank()) extras["url_slug"] = songSlug
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
            artists = artists,
            album = if (!albumTitle.isNullOrBlank()) Album(id = albumTitle, title = albumTitle) else null,
            cover = coverUrl?.toImageHolder(),
            duration = durationMs,
            extras = extras,
            streamables = streamables
        )
    }

    suspend fun getAlbumDetail(albumIdOrUrl: String, artistSlugParam: String? = null, urlSlugParam: String? = null): Pair<Album, List<Track>>? {
        val (urlArtist, urlSlug) = extractArtistAndSlug(albumIdOrUrl)
        val artistSlug = artistSlugParam ?: urlArtist
        val slug = urlSlugParam ?: urlSlug

        val json = if (!artistSlug.isNullOrBlank() && !slug.isNullOrBlank()) {
            getJson("music/album/$artistSlug/$slug")
        } else null ?: getJson("music/$albumIdOrUrl") ?: getJson("music/album/$albumIdOrUrl")

        val results = json?.get("results")?.jsonObject ?: return null
        val album = parseAlbum(results)
        val albumCover = results["image"]?.jsonPrimitive?.content ?: results["image_base"]?.jsonPrimitive?.content
        val albumTitle = results["title"]?.jsonPrimitive?.content ?: album.title
        val tracksArray = results["tracks"]?.jsonArray ?: JsonArray(emptyList())

        val tracks = tracksArray.mapNotNull { it as? JsonObject }.map { trackObj ->
            parseAlbumTrack(trackObj, albumTitle, albumCover)
        }
        return Pair(album.copy(trackCount = tracks.size.toLong()), tracks)
    }

    suspend fun getPlaylistDetail(playlistIdOrUrl: String, artistSlugParam: String? = null, urlSlugParam: String? = null): Pair<Playlist, List<Track>>? {
        val (urlArtist, urlSlug) = extractArtistAndSlug(playlistIdOrUrl)
        val artistSlug = artistSlugParam ?: urlArtist
        val slug = urlSlugParam ?: urlSlug

        val json = if (!artistSlug.isNullOrBlank() && !slug.isNullOrBlank()) {
            getJson("playlist/$artistSlug/$slug")
        } else null ?: getJson("playlist/$playlistIdOrUrl")

        val results = json?.get("results")?.jsonObject ?: return null
        val playlist = parsePlaylist(results)
        val tracksArray = results["tracks"]?.jsonArray ?: JsonArray(emptyList())

        val tracks = tracksArray.mapNotNull { it as? JsonObject }.map { trackObj ->
            parseTrack(trackObj)
        }
        return Pair(playlist.copy(trackCount = tracks.size.toLong()), tracks)
    }

    fun extractArtistAndSlug(idOrUrl: String): Pair<String?, String?> {
        val clean = idOrUrl.substringBefore("?").removePrefix("https://").removePrefix("http://").removePrefix("audiomack.com/").trim('/')
        val parts = clean.split('/')
        return when {
            parts.size >= 3 && (parts[1] == "album" || parts[1] == "playlist") -> Pair(parts[0], parts[2])
            parts.size == 2 -> Pair(parts[0], parts[1])
            else -> Pair(null, null)
        }
    }

    private val artistCache = mutableMapOf<String, Pair<Long, ArtistPageData>>()
    private val resolvedArtistSlugCache = mutableMapOf<String, String>()

    suspend fun resolveOfficialArtistSlug(artist: Artist): String {
        val existingSlug = artist.extras["url_slug"] ?: artist.extras["artist_slug"]
        val fromUploader = artist.extras["from_uploader"] == "true"
        if (fromUploader && !existingSlug.isNullOrBlank()) {
            return existingSlug
        }

        val cacheKey = artist.name.lowercase().trim()
        val cached = resolvedArtistSlugCache[cacheKey]
        if (!cached.isNullOrBlank()) {
            return cached
        }

        val searchJson = getJson("search", mapOf("q" to artist.name, "show" to "artists"))
        val results = searchJson?.get("results")?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()

        if (results.isNotEmpty()) {
            val exactMatches = results.filter { item ->
                val name = item["name"]?.jsonPrimitive?.content.orEmpty().trim()
                name.equals(artist.name.trim(), ignoreCase = true)
            }
            val candidates = if (exactMatches.isNotEmpty()) exactMatches else results

            val bestCandidate = candidates.maxByOrNull { item ->
                var score = 0L
                val verified = item["verified"]?.jsonPrimitive?.content
                val isVerified = !verified.isNullOrBlank() && !verified.equals("null", ignoreCase = true)
                if (isVerified) score += 1_000_000_000L

                val followers = item["followers_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                score += followers.coerceAtMost(500_000_000L)

                val img = item["image"]?.jsonPrimitive?.content.orEmpty()
                if (img.isNotBlank() && !img.contains("default-artist-image")) {
                    score += 100_000L
                }

                val name = item["name"]?.jsonPrimitive?.content.orEmpty().trim()
                if (name.equals(artist.name.trim(), ignoreCase = true)) {
                    score += 500_000L
                }
                score
            }

            val officialSlug = bestCandidate?.get("url_slug")?.jsonPrimitive?.content
            if (!officialSlug.isNullOrBlank()) {
                resolvedArtistSlugCache[cacheKey] = officialSlug
                return officialSlug
            }
        }

        val fallback = if (!existingSlug.isNullOrBlank()) existingSlug else extractArtistSlug(artist.id)
        resolvedArtistSlugCache[cacheKey] = fallback
        return fallback
    }

    fun extractArtistSlug(artist: Artist): String {
        val fromExtras = artist.extras["url_slug"] ?: artist.extras["artist_slug"]
        if (!fromExtras.isNullOrBlank()) return fromExtras
        return extractArtistSlug(artist.id)
    }

    fun extractArtistSlug(idOrUrl: String): String {
        val clean = idOrUrl.substringBefore("?").removePrefix("https://").removePrefix("http://").removePrefix("audiomack.com/").trim('/')
        val parts = clean.split('/')
        return when {
            parts.size >= 2 && parts[0] == "artist" -> parts[1]
            parts.isNotEmpty() && parts[0].isNotBlank() -> parts[0]
            else -> idOrUrl
        }
    }

    suspend fun getArtistPageData(artistIdOrUrl: String): ArtistPageData? {
        val rawSlug = extractArtistSlug(artistIdOrUrl)
        if (rawSlug.isBlank()) return null
        val now = System.currentTimeMillis()
        val cached = artistCache[rawSlug]
        if (cached != null && (now - cached.first) < 300_000) {
            return cached.second
        }

        val slug = if (rawSlug.all { it.isDigit() }) {
            val apiRes = getJson("artist/$rawSlug")
            val results = apiRes?.get("results")?.jsonObject
            results?.get("url_slug")?.jsonPrimitive?.content ?: rawSlug
        } else rawSlug

        val cachedBySlug = artistCache[slug]
        if (cachedBySlug != null && (now - cachedBySlug.first) < 300_000) {
            return cachedBySlug.second
        }

        // 1. Fetch web RSC
        val rscUrl = "https://audiomack.com/$slug?_rsc=ramnz"
        val rscRequest = Request.Builder()
            .url(rscUrl)
            .header("RSC", "1")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "https://audiomack.com/")
            .build()

        try {
            val response = client.newCall(rscRequest).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val line = body.lines().firstOrNull { it.contains("ArtistPage-content") }
                if (line != null) {
                    val jsonPart = line.substringAfter(":")
                    val element = json.parseToJsonElement(jsonPart) as? JsonArray
                    val children = (element?.getOrNull(3) as? JsonObject)?.get("children") as? JsonArray
                    val child0Props = (children?.getOrNull(0) as? JsonArray)?.getOrNull(3) as? JsonObject
                    val child1Props = (children?.getOrNull(1) as? JsonArray)?.getOrNull(3) as? JsonObject

                    val artistObj = (child0Props?.get("artist") ?: child1Props?.get("artist")) as? JsonObject
                    val baseArtist = if (artistObj != null) parseArtist(artistObj) else null

                    val highlightsArr = child1Props?.get("highlights") as? JsonArray
                    val highlights = highlightsArr?.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val type = obj["type"]?.jsonPrimitive?.content
                        when (type) {
                            "playlist" -> parsePlaylist(obj)
                            "album" -> parseAlbum(obj)
                            else -> parseTrack(obj)
                        }
                    }.orEmpty()

                    val topSongsArr = (child1Props?.get("topSongs") as? JsonObject)?.get("results") as? JsonArray
                    val topSongs = topSongsArr?.mapNotNull { it as? JsonObject }?.map { parseTrack(it) }.orEmpty()

                    val topAlbumsArr = (child1Props?.get("topAlbums") as? JsonObject)?.get("results") as? JsonArray
                    val topAlbums = topAlbumsArr?.mapNotNull { it as? JsonObject }?.map { parseAlbum(it) }.orEmpty()

                    val playlistsArr = (child1Props?.get("playlists") as? JsonObject)?.get("results") as? JsonArray
                        ?: (child1Props?.get("playlists") as? JsonObject)?.get("data")?.let {
                            if (it is JsonObject) it["rows"] as? JsonArray else it as? JsonArray
                        }
                    val playlists = playlistsArr?.mapNotNull { it as? JsonObject }?.map { parsePlaylist(it) }.orEmpty()

                    val featObj = child1Props?.get("playlistsFeaturing") as? JsonObject
                    val featData = featObj?.get("data")
                    val featRows = when (featData) {
                        is JsonObject -> featData["rows"] as? JsonArray ?: featData["results"] as? JsonArray
                        is JsonArray -> featData
                        else -> null
                    } ?: featObj?.get("results") as? JsonArray
                    val playlistsFeaturing = featRows?.mapNotNull { it as? JsonObject }?.map { parsePlaylist(it) }.orEmpty()

                    val likesArr = (child1Props?.get("likes") as? JsonObject)?.get("results") as? JsonArray
                    val likes = likesArr?.mapNotNull { it as? JsonObject }?.map { parseTrack(it) }.orEmpty()

                    if (baseArtist != null) {
                        val result = ArtistPageData(
                            artist = baseArtist,
                            highlights = highlights,
                            topSongs = topSongs,
                            topAlbums = topAlbums,
                            playlists = playlists,
                            playlistsFeaturing = playlistsFeaturing,
                            likes = likes
                        )
                        artistCache[rawSlug] = Pair(now, result)
                        artistCache[slug] = Pair(now, result)
                        return result
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback to official REST API
        val apiArtistJson = getJson("artist/$slug")?.get("results") as? JsonObject
        if (apiArtistJson == null) {
            val cleanQuery = slug.replace("-", " ")
            val found = searchArtists(cleanQuery, 1).firstOrNull()
            if (found != null) {
                val foundSlug = extractArtistSlug(found)
                if (foundSlug.isNotBlank() && foundSlug != slug && foundSlug != rawSlug) {
                    val fallbackData = getArtistPageData(foundSlug)
                    if (fallbackData != null) {
                        artistCache[rawSlug] = Pair(now, fallbackData)
                        return fallbackData
                    }
                }
            }
            return null
        }
        val baseArtist = parseArtist(apiArtistJson)
        val uploads = getJson("artist/$slug/uploads")?.get("results") as? JsonArray
        val topSongs = uploads?.mapNotNull { it as? JsonObject }?.map { parseTrack(it) }.orEmpty()
        val playlistsJson = getJson("artist/$slug/playlists")?.get("results") as? JsonArray
        val playlists = playlistsJson?.mapNotNull { it as? JsonObject }?.map { parsePlaylist(it) }.orEmpty()

        val result = ArtistPageData(
            artist = baseArtist,
            topSongs = topSongs,
            playlists = playlists
        )
        artistCache[rawSlug] = Pair(now, result)
        artistCache[slug] = Pair(now, result)
        return result
    }

    suspend fun getArtistUploads(artistSlug: String, page: Int = 1, limit: Int = 50): List<Track> {
        val cleanSlug = extractArtistSlug(artistSlug)
        val json = getJson("artist/$cleanSlug/uploads", mapOf("page" to page.toString(), "limit" to limit.toString()))
        val results = json?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { parseTrack(it) }
    }
}

data class ArtistPageData(
    val artist: Artist,
    val highlights: List<EchoMediaItem> = emptyList(),
    val topSongs: List<Track> = emptyList(),
    val topAlbums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val playlistsFeaturing: List<Playlist> = emptyList(),
    val likes: List<Track> = emptyList()
)

