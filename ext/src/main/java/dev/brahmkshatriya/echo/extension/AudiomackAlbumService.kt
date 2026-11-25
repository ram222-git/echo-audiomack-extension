package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonObject

/** Album metadata + ordered tracklist from `/music/album/{artist}/{slug}` or `/music/{id}`. */
class AudiomackAlbumService(
    private val api: AudiomackApiClient,
    private val mapper: AudiomackMetadataMapper
) {
    private data class Cached(val at: Long, val album: Album, val tracks: List<Track>)

    private val cache = mutableMapOf<String, Cached>()

    suspend fun load(album: Album): Album = detail(album).first

    suspend fun loadTracks(album: Album): List<Track> = detail(album).second

    suspend fun detail(album: Album): Pair<Album, List<Track>> {
        val key = album.id
        cache[key]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.album to it.tracks }

        val extrasSlug = album.extras["url_slug"].orEmpty()
        val extrasArtist = album.extras["artist_slug"].orEmpty()
        val (urlArtist, urlSlug) = AudiomackMetadataMapper.extractArtistAndSlug(album.id)
        val artistSlug = extrasArtist.ifBlank { urlArtist.orEmpty() }
        val urlSlugFinal = extrasSlug.ifBlank { urlSlug.orEmpty() }

        val json = api.getAlbum(
            albumId = album.id,
            artistSlug = artistSlug.takeIf { it.isNotBlank() },
            urlSlug = urlSlugFinal.takeIf { it.isNotBlank() }
        ) ?: throw AudiomackErrorHandler.invalidResponse(
            "This Audiomack album could not be loaded. It may have been removed."
        )

        val parsed = mapper.parseAlbum(json, artistSlug, urlSlugFinal)
        val title = json.string("title").ifBlank { album.title }
        val cover = json.stringOrNull("image") ?: json.stringOrNull("image_base")
        val tracks = json.array("tracks").orEmptyJsonArray().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            mapper.parseTrack(
                obj,
                albumTitle = title,
                albumCover = cover,
                albumId = parsed.id,
                albumOrder = (index + 1).toLong()
            ).takeIf { it.id.isNotBlank() }
        }
        val withCount = parsed.copy(trackCount = parsed.trackCount ?: tracks.size.toLong())
        cache[key] = Cached(System.currentTimeMillis(), withCount, tracks)
        return withCount to tracks
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmptyJsonArray() =
        this ?: kotlinx.serialization.json.JsonArray(emptyList())

    private companion object {
        const val CACHE_MS = 5 * 60 * 1000L
    }
}
