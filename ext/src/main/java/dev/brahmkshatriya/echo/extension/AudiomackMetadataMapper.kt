package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps the API JSON used by the official Audiomack client into Echo models.
 *
 * All values come from real API fields; no metadata is invented. Missing fields stay null.
 */
class AudiomackMetadataMapper(private val isLoggedIn: () -> Boolean = { false }) {

    fun parseTrack(
        json: JsonObject,
        albumTitle: String? = null,
        albumCover: String? = null,
        albumId: String? = null,
        albumOrder: Long? = null
    ): Track {
        val id = json.string("id").ifBlank { json.string("song_id") }
        val title = json.string("title").ifBlank { json.string("name") }.ifBlank { "Unknown Track" }
        val uploader = json.obj("uploader")
        val artistName = when (val artist = json["artist"]) {
            is JsonObject -> artist.string("name")
            else -> artist?.jsonPrimitive?.content.orEmpty()
        }.ifBlank { uploader?.string("name").orEmpty() }
        val featuring = json.string("featuring")
        val artists = parseArtists(
            artistString = listOf(artistName, featuring)
                .filter { it.isNotBlank() }
                .joinToString(", "),
            uploaderJson = uploader,
            title = title
        )
        val mainArtist = artists.firstOrNull()
        val uploaderSlug = uploader?.string("url_slug").orEmpty()
        val artistSlug = mainArtist?.extras?.get("url_slug")?.takeIf { it.isNotBlank() }
            ?: json.string("uploader_url_slug")
            ?: uploaderSlug

        val albumDetails = json.obj("album_details")
        val album = when {
            albumDetails != null -> Album(
                id = albumDetails.string("id").ifBlank { albumTitle.orEmpty() },
                title = albumDetails.string("title").ifBlank { albumTitle.orEmpty() },
                artists = parseArtists(albumDetails.string("artist"), null, null),
                cover = (albumDetails.stringOrNull("image") ?: albumCover)?.toImageHolder(),
                extras = buildMap {
                    albumDetails.stringOrNull("url_slug")?.let { put("url_slug", it) }
                    albumDetails.stringOrNull("uploader_url_slug")?.let { put("artist_slug", it) }
                }
            )
            !albumTitle.isNullOrBlank() -> Album(
                id = albumId ?: albumTitle,
                title = albumTitle,
                cover = albumCover?.takeIf { it.isNotBlank() }?.toImageHolder(),
                extras = buildMap {
                    albumId?.let { put("id", it) }
                }
            )
            !json.string("album").isBlank() -> Album(
                id = json.string("album"),
                title = json.string("album")
            )
            else -> null
        }

        val durationSeconds = json.longOrNull("duration")
            ?: json.longOrNull("tracks_total_seconds") ?: 0L
        val durationMs = durationSeconds.takeIf { it > 0 }?.times(1000)
        val coverUrl = json.stringOrNull("image") ?: json.stringOrNull("image_base") ?: albumCover
        val explicit = json.string("explicit").equals("yes", ignoreCase = true)
        val genre = json.string("genre")
        val premiumOnly = json.string("premium_user_only").equals("yes", ignoreCase = true)
        val geoRestricted = json.boolOrNull("geo_restricted") == true
        val status = json.string("status")
        val released = json.longOrNull("released") ?: json.longOrNull("original_release_date")
        val plays = json.obj("stats")?.longOrNull("plays-raw")
        val urlSlug = json.string("url_slug")
        val streamOnly = json.string("stream_only")
        val preview = json.string("preview")

        // Defense in depth: an album/release object must never become a playable track.
        val isAlbumObject = json.string("type") == "album" ||
            (json.array("tracks") != null && json.string("song_id").isBlank())
        val playable = when {
            isAlbumObject -> Track.Playable.No(
                "This is an album release, not a single playable song."
            )
            geoRestricted -> Track.Playable.RegionLocked
            premiumOnly && !isLoggedIn() ->
                Track.Playable.No("Audiomack Premium subscription required")
            status.equals("processing", true) || status.equals("failed", true) ->
                Track.Playable.No("This upload is not available yet")
            else -> Track.Playable.Yes
        }

        val extras = linkedMapOf<String, String>()
        streamOnly.takeIf { it.isNotBlank() }?.let { extras["stream_only"] = it }
        preview.takeIf { it.isNotBlank() }?.let { extras["preview"] = it }
        if (premiumOnly) extras["premium_user_only"] = "yes"
        if (geoRestricted) extras["geo_restricted"] = "yes"
        urlSlug.takeIf { it.isNotBlank() }?.let { extras["url_slug"] = it }
        artistSlug.takeIf { it.isNotBlank() }?.let { extras["artist_slug"] = it }
        album?.id?.takeIf { it.isNotBlank() }?.let { extras["album_id"] = it }
        album?.extras?.get("url_slug")?.let { extras["album_url_slug"] = it }

        val streamables = if (id.isBlank() || isAlbumObject) emptyList() else listOf(
            Streamable.server(
                id = id,
                quality = 256,
                title = "Audiomack",
                extras = extras
            )
        )

        return Track(
            id = id,
            title = title,
            cover = coverUrl?.takeIf { it.isNotBlank() }?.toImageHolder(),
            artists = artists,
            album = album,
            duration = durationMs,
            plays = plays,
            releaseDate = released?.takeIf { it > 0 }?.let { Date(it * 1000) },
            description = json.stringOrNull("description"),
            genres = if (genre.isNotBlank()) listOf(genre) else emptyList(),
            isrc = json.stringOrNull("isrc"),
            albumOrderNumber = albumOrder,
            isExplicit = explicit,
            extras = extras,
            isPlayable = playable,
            streamables = streamables,
            isLikeable = true,
            isSaveable = true
        )
    }

    fun parseAlbum(
        json: JsonObject,
        fallbackArtistSlug: String? = null,
        fallbackUrlSlug: String? = null
    ): Album {
        val id = json.string("id").ifBlank { json.string("album_id") }
        val title = json.string("title").ifBlank { "Unknown Album" }
        val uploader = json.obj("uploader")
        val artistName = when (val artist = json["artist"]) {
            is JsonObject -> artist.string("name")
            else -> artist?.jsonPrimitive?.content.orEmpty()
        }.ifBlank { uploader?.string("name").orEmpty() }
        val artists = parseArtists(artistName, uploader, title)
        val tracks = json.array("tracks")
        val trackCount = json.longOrNull("track_count") ?: tracks?.size?.toLong()
        val durationSeconds = json.longOrNull("duration")
            ?: json.longOrNull("tracks_total_seconds")
            ?: 0L
        val durationMs = if (durationSeconds > 0) durationSeconds * 1000 else sumDuration(tracks)
        val coverUrl = json.stringOrNull("image") ?: json.stringOrNull("image_base")
        val release = json.longOrNull("released") ?: json.longOrNull("original_release_date")
        val urlSlug = json.string("url_slug").ifBlank { fallbackUrlSlug.orEmpty() }
        val artistSlug = artists.firstOrNull()?.extras?.get("url_slug")
            ?: uploader?.string("url_slug")
            ?: fallbackArtistSlug
        val extras = linkedMapOf<String, String>()
        urlSlug.takeIf { it.isNotBlank() }?.let { extras["url_slug"] = it }
        artistSlug?.takeIf { it.isNotBlank() }?.let { extras["artist_slug"] = it }
        val explicit = tracks?.any {
            (it as? JsonObject)?.string("explicit").equals("yes", ignoreCase = true)
        } == true

        return Album(
            id = id,
            title = title,
            cover = coverUrl?.takeIf { it.isNotBlank() }?.toImageHolder(),
            artists = artists,
            trackCount = trackCount,
            duration = durationMs,
            releaseDate = release?.takeIf { it > 0 }?.let { Date(it * 1000) },
            description = json.stringOrNull("description"),
            label = json.stringOrNull("label_name") ?: json.stringOrNull("pline"),
            isExplicit = explicit,
            extras = extras,
            isSaveable = true,
            isLikeable = false
        )
    }

    fun parseArtist(json: JsonObject): Artist {
        val rawId = json.string("id")
        val urlSlug = json.string("url_slug").ifBlank {
            json.string("artist_slug").ifBlank { rawId }
        }
        val name = json.string("name").ifBlank { json.string("artist") }.ifBlank { "Unknown Artist" }
        val coverUrl = json.stringOrNull("image") ?: json.stringOrNull("image_base")
        val bannerUrl = json.stringOrNull("image_banner")
        val verifiedRaw = json.string("verified")
        val verified = verifiedRaw.isNotBlank() && !verifiedRaw.equals("no", true)
        val followers = json.longOrNull("followers_count")
        val plays = json.obj("stats")?.longOrNull("plays-raw")
        val subtitle = listOfNotNull(
            when {
                verifiedRaw.equals("yes", true) -> "Verified Artist"
                verifiedRaw.isNotBlank() && !verifiedRaw.equals("no", true) ->
                    verifiedRaw.replaceFirstChar { it.uppercase() } + " Artist"
                else -> null
            },
            followers?.let { "%,d Followers".format(it) },
            plays?.let { "%,d Plays".format(it) }
        ).joinToString(" • ").ifBlank {
            urlSlug.takeIf { it.isNotBlank() }?.let { "@$it" }
        }

        val extras = linkedMapOf<String, String>()
        urlSlug.takeIf { it.isNotBlank() }?.let {
            extras["url_slug"] = it
            extras["artist_slug"] = it
            // This slug came from the API object, so it is safe to use for navigation.
            extras["slug_verified"] = "true"
        }
        rawId.takeIf { it.isNotBlank() }?.let { extras["id"] = it }
        verifiedRaw.takeIf { it.isNotBlank() }?.let { extras["verified"] = it }

        return Artist(
            id = urlSlug.ifBlank { rawId },
            name = name,
            cover = coverUrl?.takeIf { it.isNotBlank() }?.toImageHolder(),
            background = (bannerUrl ?: coverUrl)?.takeIf { it.isNotBlank() }?.toImageHolder(),
            bio = json.stringOrNull("bio"),
            banners = listOfNotNull(bannerUrl?.takeIf { it.isNotBlank() }?.toImageHolder()),
            subtitle = subtitle,
            extras = extras
        )
    }

    fun parsePlaylist(
        json: JsonObject,
        fallbackAuthorSlug: String? = null,
        fallbackUrlSlug: String? = null,
        isEditable: Boolean = false
    ): Playlist {
        val id = json.string("id").ifBlank { json.string("playlistId") }
        val title = json.string("title").ifBlank { "Unknown Playlist" }
        val coverUrl = json.stringOrNull("image") ?: json.stringOrNull("image_base")
        val tracks = json.array("tracks")
        val trackCount = json.longOrNull("track_count") ?: tracks?.size?.toLong()
        val durationSeconds = json.longOrNull("tracks_total_seconds")
            ?: json.longOrNull("duration") ?: 0L
        val created = json.longOrNull("created") ?: json.longOrNull("creation_date")

        val authorObj = json.obj("artist") ?: json.obj("uploader")
        val authorName = authorObj?.string("name").orEmpty().ifBlank { "Audiomack" }
        val authorSlug = authorObj?.string("url_slug").orEmpty()
            .ifBlank { json.string("uploader_url_slug") }
            .ifBlank { fallbackAuthorSlug.orEmpty() }
        val urlSlug = json.string("url_slug").ifBlank { fallbackUrlSlug.orEmpty() }
        val isPrivate = json.string("private").equals("yes", ignoreCase = true)
        val extras = linkedMapOf<String, String>()
        urlSlug.takeIf { it.isNotBlank() }?.let { extras["url_slug"] = it }
        authorSlug.takeIf { it.isNotBlank() }?.let { extras["artist_slug"] = it }
        json.string("genre").takeIf { it.isNotBlank() }?.let { extras["genre"] = it }

        return Playlist(
            id = id,
            title = title,
            isEditable = isEditable,
            isPrivate = isPrivate,
            cover = coverUrl?.takeIf { it.isNotBlank() }?.toImageHolder(),
            authors = listOf(
                Artist(
                    id = authorSlug.ifBlank { "audiomack" },
                    name = authorName,
                    cover = authorObj?.stringOrNull("image")?.toImageHolder(),
                    extras = buildMap {
                        authorSlug.takeIf { it.isNotBlank() }?.let {
                            put("url_slug", it)
                            put("artist_slug", it)
                            put("slug_verified", "true")
                        }
                    }
                )
            ),
            trackCount = trackCount,
            duration = if (durationSeconds > 0) durationSeconds * 1000 else sumDuration(tracks),
            creationDate = created?.takeIf { it > 0 }?.let { Date(it * 1000) },
            description = json.stringOrNull("description"),
            extras = extras,
            isSaveable = true
        )
    }

    fun parseMediaItem(json: JsonObject): EchoMediaItem? {
        val type = json.string("type").lowercase()
        return when {
            type == "artist" || (json.containsKey("followers_count") && !json.containsKey("title")) ->
                parseArtist(json)
            type == "album" -> parseAlbum(json)
            type == "playlist" -> parsePlaylist(json)
            type == "song" || type == "music" -> parseTrack(json)
            json.containsKey("song_id") || json.containsKey("duration") -> parseTrack(json)
            else -> null
        }
    }

    fun parseArtists(
        artistString: String?,
        uploaderJson: JsonObject? = null,
        title: String? = null
    ): List<Artist> {
        val uploaderName = uploaderJson?.stringOrNull("name")?.trim()
        val uploaderSlug = uploaderJson?.string("url_slug")?.trim().orEmpty()
        val uploaderId = uploaderJson?.string("id")?.trim().orEmpty()

        val rawArtistString = artistString?.trim().orEmpty()
        val baseString = if (
            rawArtistString.isNotBlank() &&
            !rawArtistString.equals("Unknown Artist", ignoreCase = true)
        ) rawArtistString else uploaderName.orEmpty()

        if (baseString.isBlank()) {
            return listOf(
                Artist(
                    id = uploaderSlug.ifBlank { uploaderId.ifBlank { "unknown" } },
                    name = "Unknown Artist"
                )
            )
        }

        val splitRegex = Regex(
            """(?i)\s*(?:,\s*|\s*&\s*|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+[xX]\s+|\s*/\s*|\s*\\\s*|\s*\|\s*|\s+with\s+|;\s*)\s*"""
        )
        val names = mutableListOf<String>()
        baseString.split(splitRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { names.add(it) }

        if (!title.isNullOrBlank()) {
            val featRegex = Regex("""(?i)[\(\[](?:feat\.?|ft\.?|featuring)\s+([^\]\)]+)[\)\]]""")
            featRegex.find(title)?.groupValues?.getOrNull(1)?.let { feat ->
                feat.split(splitRegex).map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { part ->
                        if (names.none { it.equals(part, ignoreCase = true) }) names.add(part)
                    }
            }
        }

        val distinct = names.distinctBy { it.lowercase() }
        if (distinct.isEmpty()) {
            return listOf(
                Artist(
                    id = uploaderSlug.ifBlank { uploaderId.ifBlank { "unknown" } },
                    name = baseString
                )
            )
        }

        return distinct.mapIndexed { index, name ->
            val isUploader = !uploaderName.isNullOrBlank() && name.equals(uploaderName, true)
            // Never reuse the uploader's slug for a differently named credit; that is how a
            // featured artist used to open the uploader's profile instead of its own.
            val slugFromUploader = isUploader && uploaderSlug.isNotBlank() ||
                index == 0 && uploaderSlug.isNotBlank() && uploaderName.isNullOrBlank()
            val slug = when {
                slugFromUploader -> uploaderSlug
                else -> slugify(name)
            }
            val extras = linkedMapOf<String, String>()
            if (slug.isNotBlank()) {
                extras["url_slug"] = slug
                extras["artist_slug"] = slug
                // Only slugs returned by the API are trusted. A slug made from a featured
                // artist's name may belong to a different artist, so it stays a candidate
                // until the artist service resolves the real numeric id.
                if (slugFromUploader) extras["slug_verified"] = "true"
            }
            if (isUploader) extras["from_uploader"] = "true"
            Artist(
                id = if (isUploader && uploaderId.isNotBlank()) uploaderId else slug.ifBlank { name },
                name = name,
                extras = extras
            )
        }
    }

    companion object {
        fun slugify(value: String): String =
            value.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        fun extractArtistAndSlug(idOrUrl: String): Pair<String?, String?> {
            val clean = idOrUrl.substringBefore("?")
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("audiomack.com/")
                .trim('/')
            val parts = clean.split('/')
            return when {
                parts.size >= 3 && (parts[1] == "album" || parts[1] == "playlist" || parts[1] == "song") ->
                    Pair(parts[0], parts[2])
                parts.size == 2 -> Pair(parts[0], parts[1])
                else -> Pair(null, null)
            }
        }

        fun extractSlug(idOrUrl: String): String {
            val clean = idOrUrl.substringBefore("?")
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("audiomack.com/")
                .trim('/')
            val parts = clean.split('/')
            return when {
                parts.size >= 2 && parts[0] == "artist" -> parts[1]
                parts.isNotEmpty() && parts[0].isNotBlank() -> parts[0]
                else -> idOrUrl
            }
        }

        private fun sumDuration(tracks: kotlinx.serialization.json.JsonArray?): Long? {
            if (tracks == null) return null
            val total = tracks.sumOf { element ->
                ((element as? JsonObject)?.longOrNull("duration") ?: 0L) * 1000
            }
            return total.takeIf { it > 0 }
        }
    }
}
