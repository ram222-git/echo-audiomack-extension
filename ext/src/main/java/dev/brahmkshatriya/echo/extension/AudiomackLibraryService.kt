package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonObject

/**
 * Authenticated library operations.
 *
 * Endpoints are taken from the official client's `ApiFavorites`, `ApiFollow`, `ApiPlaylists`
 * and `ApiRecentlyPlayed`. These require a real account, so they are marked BLOCKED in the
 * test report unless credentials are supplied.
 */
class AudiomackLibraryService(
    private val api: AudiomackApiClient,
    private val mapper: AudiomackMetadataMapper,
    private val auth: AudiomackAuthManager,
    private val playlists: AudiomackPlaylistService,
    private val pageSize: Int = 20
) {
    private var favoriteCache: Pair<Long, Set<String>>? = null
    private var followingCache: Pair<Long, Set<String>>? = null

    suspend fun likedTracks(page: Int): Page<Track> {
        val slug = auth.requireSlug()
        val raw = api.getArtistFavorites(slug, "songs", page)
        return Page(
            raw.mapNotNull { obj -> runCatching { mapper.parseTrack(obj) }.getOrNull() }
                .filter { it.id.isNotBlank() },
            if (raw.size >= pageSize) (page + 1).toString() else null
        )
    }

    suspend fun savedAlbums(page: Int): Page<Album> {
        val slug = auth.requireSlug()
        val raw = api.getArtistFavorites(slug, "albums", page)
        return Page(
            raw.mapNotNull { obj -> runCatching { mapper.parseAlbum(obj) }.getOrNull() }
                .filter { it.id.isNotBlank() },
            if (raw.size >= pageSize) (page + 1).toString() else null
        )
    }

    suspend fun myPlaylists(page: Int, limit: Int = 50): Page<Playlist> {
        val raw = api.getMyPlaylists(page, limit)
        return Page(
            raw.mapNotNull { obj ->
                runCatching { mapper.parsePlaylist(obj, isEditable = true) }.getOrNull()
            }.filter { it.id.isNotBlank() },
            if (raw.size >= limit) (page + 1).toString() else null
        )
    }

    suspend fun recentlyPlayed(): List<Track> {
        val raw = api.getListenHistory()
        return raw.mapNotNull { obj ->
            val trackObj = obj.obj("music") ?: obj.obj("track") ?: obj
            runCatching { mapper.parseTrack(trackObj) }.getOrNull()
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    }

    suspend fun following(): List<Artist> {
        val slug = auth.requireSlug()
        val raw = api.getArtistFollowing(slug)
        val artists = raw.mapNotNull { obj ->
            val artistObj = obj.obj("artist") ?: obj
            runCatching { mapper.parseArtist(artistObj) }.getOrNull()
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
        cacheFollowing(artists)
        return artists
    }

    suspend fun isFavorite(item: EchoMediaItem): Boolean {
        val ids = favoriteIds()
        return ids.contains(favoriteKey(item))
    }

    suspend fun setFavorite(item: EchoMediaItem, shouldSave: Boolean) {
        when (item) {
            is Track -> api.setMusicFavorite(item.id, item.album?.id, shouldSave)
            is Album -> api.setMusicFavorite(item.id, item.id, shouldSave)
            is Playlist -> api.setPlaylistFavorite(item.id, shouldSave)
            is Artist -> throw ClientException.NotSupported(
                "Audiomack does not expose a favorite/save action for artists."
            )
            else -> throw ClientException.NotSupported(
                "This Audiomack item cannot be saved to the library."
            )
        }
        favoriteCache = null
    }

    suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val slug = when (item) {
            is Artist -> AudiomackMetadataMapper.extractSlug(item.id)
            else -> return false
        }
        val cached = followingCache
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_MS) {
            return cached.second.contains(slug)
        }
        following()
        return followingCache?.second?.contains(slug) == true
    }

    suspend fun followersCount(item: EchoMediaItem): Long? {
        val artist = item as? Artist ?: return null
        val slug = AudiomackMetadataMapper.extractSlug(artist.id)
        val json = api.getArtist(slug) ?: return null
        return json.longOrNull("followers_count")
    }

    suspend fun setFollowing(item: EchoMediaItem, shouldFollow: Boolean) {
        val artist = item as? Artist
            ?: throw ClientException.NotSupported("Only Audiomack artists can be followed.")
        val slug = AudiomackMetadataMapper.extractSlug(artist.id)
        if (slug.isBlank()) return
        api.setFollowing(slug, shouldFollow)
        followingCache = null
    }

    suspend fun editablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        val mine = myPlaylists(1, 50).data
        val result = mutableListOf<Pair<Playlist, Boolean>>()
        for (playlist in mine.take(20)) {
            val contains = if (track == null) false else {
                runCatching {
                    val detail = api.getPlaylist(
                        playlistId = playlist.id,
                        artistSlug = playlist.extras["artist_slug"],
                        urlSlug = playlist.extras["url_slug"]
                    )
                    detail?.array("tracks")?.any { element ->
                        val obj = element as? JsonObject ?: return@any false
                        obj.string("id") == track.id || obj.string("song_id") == track.id
                    } ?: false
                }.getOrDefault(false)
            }
            result.add(playlist to contains)
        }
        return result
    }

    suspend fun createPlaylist(title: String, description: String?): Playlist {
        if (title.isBlank()) {
            throw AudiomackException(null, null, "A playlist title is required.")
        }
        val json = api.createPlaylist(title.trim(), description)
        val parsed = mapper.parsePlaylist(json, isEditable = true)
        if (parsed.id.isNotBlank()) return parsed
        // The create response shape is not guaranteed; read back the newest playlist.
        val first = myPlaylists(1, 10).data.firstOrNull()
            ?: throw AudiomackErrorHandler.invalidResponse(
                "The playlist was created but Audiomack did not return it."
            )
        return first
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        api.deletePlaylist(playlist.id)
        playlists.invalidate(playlist.id)
    }

    suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) {
        api.editPlaylist(
            playlistId = playlist.id,
            title = title,
            description = description,
            isPrivate = playlist.isPrivate,
            genre = genreSlug(playlist)
        )
        playlists.invalidate(playlist.id)
    }

    suspend fun addTracks(playlist: Playlist, tracks: List<Track>) {
        tracks.forEach { api.addTrackToPlaylist(playlist.id, it.id) }
        playlists.invalidate(playlist.id)
    }

    suspend fun removeTracks(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        val ids = indexes.mapNotNull { index -> tracks.getOrNull(index)?.id }
        api.removeTracksFromPlaylist(playlist.id, ids)
        playlists.invalidate(playlist.id)
    }

    suspend fun setPrivacy(playlist: Playlist, isPrivate: Boolean) {
        api.editPlaylist(
            playlistId = playlist.id,
            title = playlist.title,
            description = playlist.description,
            isPrivate = isPrivate,
            genre = genreSlug(playlist)
        )
        playlists.invalidate(playlist.id)
    }

    /** The update endpoint validates lowercase app genre slugs, not display labels. */
    private fun genreSlug(playlist: Playlist): String {
        val raw = playlist.extras["genre"]?.trim()?.lowercase().orEmpty()
        return when (raw) {
            "r&b/soul", "r&b", "rnb" -> "rnb"
            "hip-hop/rap", "hip-hop", "rap" -> "rap"
            "dance hall", "dancehall" -> "dancehall"
            "dj mix", "djmix", "dj-mix" -> "dj-mix"
            "nature sounds", "nature-sounds" -> "nature-sounds"
            else -> raw.ifBlank { "other" }
        }
    }

    fun invalidateAll() {
        favoriteCache = null
        followingCache = null
    }

    private suspend fun favoriteIds(): Set<String> {
        favoriteCache?.let {
            if (System.currentTimeMillis() - it.first < CACHE_MS) return it.second
        }
        val slug = auth.requireSlug()
        val ids = mutableSetOf<String>()
        api.getArtistFavorites(slug, "songs", 1).forEach { obj ->
            val id = obj.string("id").ifBlank { obj.string("song_id") }
            if (id.isNotBlank()) ids.add("track:$id")
        }
        api.getArtistFavorites(slug, "albums", 1).forEach { obj ->
            val id = obj.string("id")
            if (id.isNotBlank()) ids.add("album:$id")
        }
        runCatching {
            api.getArtistFavorites(slug, "playlists", 1).forEach { obj ->
                val id = obj.string("id")
                if (id.isNotBlank()) ids.add("playlist:$id")
            }
        }
        val value = ids.toSet()
        favoriteCache = System.currentTimeMillis() to value
        return value
    }

    private fun cacheFollowing(artists: List<Artist>) {
        followingCache = System.currentTimeMillis() to artists.map { it.id }.toSet()
    }

    private fun favoriteKey(item: EchoMediaItem): String = when (item) {
        is Track -> "track:${item.id}"
        is Album -> "album:${item.id}"
        is Playlist -> "playlist:${item.id}"
        else -> "${item::class.simpleName}:${item.id}"
    }

    private companion object {
        const val CACHE_MS = 60 * 1000L
    }
}
