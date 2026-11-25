package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track

/** Home/discovery shelves backed by `/music/trending`, `/music/recent` and playlist categories. */
class AudiomackCatalogService(
    private val api: AudiomackApiClient,
    private val mapper: AudiomackMetadataMapper,
    private val pageSize: Int = 20
) {
    data class Category(
        val id: String,
        val title: String,
        val slug: String,
        val image: ImageHolder? = null
    )

    suspend fun trending(page: Int): Page<Track> {
        val raw = api.getTrending(page)
        return Page(
            raw.filterNot { it.string("type") == "album" }
                .map { mapper.parseTrack(it) }
                .filter { it.id.isNotBlank() },
            if (raw.size >= pageSize) (page + 1).toString() else null
        )
    }

    suspend fun recent(page: Int): Page<Track> {
        val raw = api.getRecent(page)
        return Page(
            raw.filterNot { it.string("type") == "album" }
                .map { mapper.parseTrack(it) }
                .filter { it.id.isNotBlank() },
            if (raw.size >= pageSize) (page + 1).toString() else null
        )
    }

    suspend fun categories(): List<Category> =
        api.getPlaylistCategories().mapNotNull { obj ->
            val slug = obj.string("url_slug")
            val title = obj.string("title")
            if (slug.isBlank() || title.isBlank()) null
            else Category(
                id = obj.string("id").ifBlank { slug },
                title = title,
                slug = slug,
                image = obj.stringOrNull("image")?.toImageHolder()
            )
        }

    suspend fun playlistsForCategory(slug: String, page: Int): List<Playlist> =
        api.getPlaylistsForCategory(slug, page).mapNotNull { obj ->
            when (obj.string("type")) {
                "", "playlist" -> runCatching { mapper.parsePlaylist(obj) }.getOrNull()
                else -> null
            }
        }

    suspend fun artistsForQuery(query: String): List<Artist> =
        api.search(query, "artists", 1).mapNotNull { runCatching { mapper.parseArtist(it) }.getOrNull() }
}
