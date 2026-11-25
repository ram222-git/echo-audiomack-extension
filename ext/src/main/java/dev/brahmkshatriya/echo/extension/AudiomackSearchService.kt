package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** Catalog search, quick suggestions and pagination over Audiomack's `/search` + `/livesearch`. */
class AudiomackSearchService(
    private val api: AudiomackApiClient,
    private val mapper: AudiomackMetadataMapper,
    private val pageSize: Int = 20
) {
    suspend fun search(query: String, show: String, page: Int): Page<EchoMediaItem> {
        if (query.isBlank()) return Page(emptyList(), null)
        val raw = api.search(query, show, page)
        val items = raw.mapNotNull { mapper.parseMediaItem(it) }
        val next = if (raw.size >= pageSize) (page + 1).toString() else null
        return Page(items.distinctBy { "${it::class.simpleName}:${it.id}" }, next)
    }

    suspend fun searchAll(query: String, page: Int): Page<EchoMediaItem> = coroutineScope {
        val songs = async { search(query, "songs", page) }
        val albums = async { search(query, "albums", page) }
        val artists = async { search(query, "artists", page) }
        val playlists = async { search(query, "playlists", page) }
        val pages = listOf(songs.await(), albums.await(), artists.await(), playlists.await())
        Page(
            pages.flatMap { it.data }.distinctBy { "${it::class.simpleName}:${it.id}" },
            if (pages.any { it.continuation != null }) (page + 1).toString() else null
        )
    }

    suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val root = api.liveSearch(query.trim()) ?: return emptyList()
        val items = mutableListOf<QuickSearchItem>()
        val seenQueries = mutableSetOf<String>()
        val seenMedia = mutableSetOf<String>()

        fun addMedia(obj: JsonObject) {
            val media = mapper.parseMediaItem(obj) ?: return
            val key = "${media::class.simpleName}:${media.id}"
            if (media.id.isNotBlank() && seenMedia.add(key)) {
                items.add(QuickSearchItem.Media(media, searched = false))
            }
        }

        root.obj("verified_artist")?.let { artist ->
            val title = artist.string("name")
            if (title.isNotBlank() && seenQueries.add(title.lowercase())) {
                items.add(QuickSearchItem.Query(title, searched = false))
            }
            addMedia(artist)
        }
        root.obj("verified_playlist")?.let { addMedia(it) }

        for (obj in root["results"].objects()) {
            val title = if (obj.string("type") == "artist") {
                obj.string("name")
            } else {
                obj.string("title")
            }.trim()
            if (title.isNotBlank() && seenQueries.add(title.lowercase())) {
                items.add(QuickSearchItem.Query(title, searched = false))
            }
            addMedia(obj)
        }
        return items
    }
}
