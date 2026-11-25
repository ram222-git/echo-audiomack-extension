package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonObject

/** Artist profile and shelves built only from the artist and recommendation endpoints. */
class AudiomackArtistService(
    private val api: AudiomackApiClient,
    private val mapper: AudiomackMetadataMapper
) {
    data class ArtistData(
        val artist: Artist,
        val topSongs: List<Track> = emptyList(),
        val albums: List<Album> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val pinned: List<EchoMediaItem> = emptyList(),
        val related: List<Artist> = emptyList()
    )

    private data class Cached(val at: Long, val data: ArtistData)

    private val cache = mutableMapOf<String, Cached>()
    private val resolvedNames = mutableMapOf<String, String>()

    /**
     * Resolves the API lookup key for an artist. Track payloads only carry the uploader's
     * numeric id; featured artists arrive as names. Turning a featured name into a slug and
     * opening it can land on a different artist, so resolve the name to Audiomack's real
     * numeric artist id (`artist?id=`) instead.
     */
    suspend fun resolveKey(artist: Artist): String {
        numericId(artist)?.let { return it }

        // A full profile URL (or artist/<slug>) is an explicit reference, not a guessed name.
        if (artist.id.startsWith("http://") || artist.id.startsWith("https://") ||
            artist.id.contains("/")
        ) {
            AudiomackMetadataMapper.extractSlug(artist.id)
                .takeIf { it.isNotBlank() }?.let { return it }
        }

        val candidateSlug = artist.extras["url_slug"] ?: artist.extras["artist_slug"]
        if (artist.extras["slug_verified"] == "true" && !candidateSlug.isNullOrBlank()) {
            return candidateSlug
        }

        val name = artist.name.trim()
        if (name.isNotBlank()) {
            val cacheKey = name.lowercase()
            resolvedNames[cacheKey]?.let { return it }
            val match = api.search(name, "artists", 1)
                .filter { it.string("name").equals(name, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<JsonObject> {
                        it.string("status").equals("active", ignoreCase = true)
                    }.thenByDescending { it.longOrNull("followers_count") ?: 0L }
                )
                .firstOrNull()
            val key = match?.let { it.string("id").ifBlank { it.string("url_slug") } }.orEmpty()
            if (key.isNotBlank()) {
                resolvedNames[cacheKey] = key
                return key
            }
        }

        // Fall back to the candidate slug only after confirming it is the same artist.
        if (!candidateSlug.isNullOrBlank() && slugBelongsToArtist(candidateSlug, name)) {
            return candidateSlug
        }
        // Callers that only supply a bare slug and no name (older saved items) still work,
        // but the profile must exist before it is used.
        if (name.isBlank() && artist.id.isNotBlank()) {
            val bareSlug = AudiomackMetadataMapper.extractSlug(artist.id)
            if (bareSlug.isNotBlank() && slugBelongsToArtist(bareSlug, "")) return bareSlug
        }
        throw AudiomackErrorHandler.invalidResponse(
            "Audiomack has no artist profile for \"${artist.name}\"."
        )
    }

    private fun numericId(artist: Artist): String? =
        listOf(artist.extras["id"], artist.extras["artist_id"], artist.id)
            .firstOrNull { it != null && it.isNotBlank() && it.all(Char::isDigit) }

    private suspend fun slugBelongsToArtist(slug: String, name: String): Boolean {
        val json = try {
            api.getArtist(slug)
        } catch (e: AudiomackException) {
            if (e.statusCode == 404) return false else throw e
        } ?: return false
        return name.isBlank() || json.string("name").equals(name, ignoreCase = true)
    }

    suspend fun load(artist: Artist): Artist {
        val slug = resolveKey(artist)
        if (slug.isBlank()) return artist
        val json = api.getArtist(slug) ?: return artist
        val parsed = mapper.parseArtist(json)
        return artist.copy(
            id = parsed.id.ifBlank { artist.id },
            name = parsed.name.ifBlank { artist.name },
            cover = parsed.cover ?: artist.cover,
            bio = parsed.bio ?: artist.bio,
            background = parsed.background ?: parsed.cover ?: artist.background,
            subtitle = parsed.subtitle ?: artist.subtitle,
            extras = (if (parsed.extras.isNotEmpty()) parsed.extras else artist.extras) +
                mapOf(
                    "url_slug" to parsed.id,
                    "artist_slug" to parsed.id,
                    "slug_verified" to "true"
                )
        )
    }

    suspend fun loadData(artist: Artist): ArtistData {
        val slug = resolveKey(artist)
        cache[slug]?.let {
            if (System.currentTimeMillis() - it.at < CACHE_MS) return it.data
        }

        val json = api.getArtist(slug)
        val base = if (json != null) mapper.parseArtist(json) else artist
        val effectiveSlug = base.id.ifBlank { slug }

        val uploads = api.getArtistUploads(effectiveSlug, page = 1)
        val songs = mutableListOf<Track>()
        val albums = mutableListOf<Album>()
        val playlists = mutableListOf<Playlist>()
        for (obj in uploads) {
            when (obj.string("type")) {
                "album" -> albums.add(mapper.parseAlbum(obj, effectiveSlug))
                "playlist" -> playlists.add(mapper.parsePlaylist(obj, effectiveSlug))
                else -> {
                    val track = mapper.parseTrack(obj)
                    if (track.id.isNotBlank()) songs.add(track)
                }
            }
        }
        playlists.addAll(api.getArtistPlaylists(effectiveSlug).map { mapper.parsePlaylist(it, effectiveSlug) })

        val pinned = api.getArtistPinned(effectiveSlug).mapNotNull { mapper.parseMediaItem(it) }
        val artistId = base.extras["id"].orEmpty()
        // Related-artist recommendations are account-only; omit the shelf when signed out
        // instead of failing the whole artist page. Other failures still propagate.
        val related = if (artistId.isNotBlank()) {
            try {
                api.getRelatedArtists(artistId).mapNotNull { obj ->
                    runCatching { mapper.parseArtist(obj) }.getOrNull()
                }
            } catch (e: dev.brahmkshatriya.echo.common.helpers.ClientException.LoginRequired) {
                emptyList()
            } catch (e: AudiomackException) {
                if (e.statusCode == 401 || e.statusCode == 403) emptyList() else throw e
            }
        } else emptyList()

        val data = ArtistData(
            artist = base,
            topSongs = songs.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id },
            pinned = pinned.distinctBy { "${it::class.simpleName}:${it.id}" },
            related = related.distinctBy { it.id }
        )
        cache[slug] = Cached(System.currentTimeMillis(), data)
        return data
    }

    suspend fun uploadsPage(artist: Artist, page: Int, pageSize: Int = 20): Page<Track> {
        val slug = resolveKey(artist)
        val raw = api.getArtistUploads(slug, page)
        val tracks = raw.mapNotNull { obj ->
            if (obj.string("type") == "album" || obj.string("type") == "playlist") null
            else mapper.parseTrack(obj).takeIf { it.id.isNotBlank() }
        }
        return Page(tracks, if (raw.size >= pageSize) (page + 1).toString() else null)
    }

    fun invalidate(slug: String) {
        cache.remove(slug)
    }

    private companion object {
        const val CACHE_MS = 5 * 60 * 1000L
    }
}
