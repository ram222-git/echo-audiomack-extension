package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonObject

/** Playlist metadata + complete tracklist. Live-verified: returns all 100 tracks for a 100-track playlist. */
class AudiomackPlaylistService(
    private val api: AudiomackApiClient,
    private val mapper: AudiomackMetadataMapper
) {
    private data class Cached(val at: Long, val playlist: Playlist, val tracks: List<Track>)

    private val cache = mutableMapOf<String, Cached>()

    suspend fun load(playlist: Playlist, isEditable: Boolean = playlist.isEditable): Playlist =
        detail(playlist, isEditable).first

    suspend fun loadTracks(playlist: Playlist): List<Track> = detail(playlist).second

    suspend fun detail(
        playlist: Playlist,
        isEditable: Boolean = playlist.isEditable
    ): Pair<Playlist, List<Track>> {
        cache[playlist.id]?.let {
            if (System.currentTimeMillis() - it.at < CACHE_MS) return it.playlist to it.tracks
        }

        val extrasSlug = playlist.extras["url_slug"].orEmpty()
        val extrasAuthor = playlist.extras["artist_slug"].orEmpty()
        val (urlAuthor, urlSlug) = AudiomackMetadataMapper.extractArtistAndSlug(playlist.id)
        val author = extrasAuthor.ifBlank { urlAuthor.orEmpty() }
        val slug = extrasSlug.ifBlank { urlSlug.orEmpty() }

        val json = api.getPlaylist(
            playlistId = playlist.id,
            artistSlug = author.takeIf { it.isNotBlank() },
            urlSlug = slug.takeIf { it.isNotBlank() }
        ) ?: throw AudiomackErrorHandler.invalidResponse(
            "This Audiomack playlist could not be loaded. It may have been removed or made private."
        )

        val parsed = mapper.parsePlaylist(json, author, slug, isEditable)
        val tracks = json.array("tracks").orEmptyJsonArray().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") == "album") return@mapNotNull null
            mapper.parseTrack(obj).takeIf { it.id.isNotBlank() }
        }
        val withCount = parsed.copy(trackCount = parsed.trackCount ?: tracks.size.toLong())
        cache[playlist.id] = Cached(System.currentTimeMillis(), withCount, tracks)
        return withCount to tracks
    }

    fun invalidate(id: String) {
        cache.remove(id)
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmptyJsonArray() =
        this ?: kotlinx.serialization.json.JsonArray(emptyList())

    private companion object {
        const val CACHE_MS = 3 * 60 * 1000L
    }
}
