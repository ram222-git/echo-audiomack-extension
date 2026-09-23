package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AudiomackExtension : ExtensionClient, HomeFeedClient, TrackClient, QuickSearchClient {

    private lateinit var settings: Settings
    private val api by lazy {
        AudiomackApi(
            consumerKeyProvider = {
                if (::settings.isInitialized) {
                    settings.getString("consumer_key")?.takeIf { it.isNotBlank() }
                        ?: AudiomackOAuth.DEFAULT_CONSUMER_KEY
                } else AudiomackOAuth.DEFAULT_CONSUMER_KEY
            },
            consumerSecretProvider = {
                if (::settings.isInitialized) {
                    settings.getString("consumer_secret")?.takeIf { it.isNotBlank() }
                        ?: AudiomackOAuth.DEFAULT_CONSUMER_SECRET
                } else AudiomackOAuth.DEFAULT_CONSUMER_SECRET
            }
        )
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingTextInput(
                title = "Audiomack Consumer Key",
                key = "consumer_key",
                summary = "Custom OAuth consumer key (Optional)",
                defaultValue = ""
            ),
            SettingTextInput(
                title = "Audiomack Consumer Secret",
                key = "consumer_secret",
                summary = "Custom OAuth consumer secret (Optional)",
                defaultValue = ""
            )
        )
    }

    override suspend fun onInitialize() {
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab(id = "discover", title = "Discover"),
            Tab(id = "charts", title = "Charts"),
            Tab(id = "browse", title = "Browse")
        )
        return Feed(tabs) { tab ->
            val tabId = tab?.id ?: "discover"
            val shelves: List<Shelf> = when (tabId) {
                "charts" -> {
                    val topSongs = api.getChartSongs()
                    val topAlbums = api.getChartAlbums()
                    val topPlaylists = api.getChartPlaylists()
                    listOf(
                        Shelf.Lists.Tracks(
                            id = "top_songs",
                            title = "Top Songs",
                            list = topSongs
                        ),
                        Shelf.Lists.Items(
                            id = "top_albums",
                            title = "Top Albums",
                            list = topAlbums
                        ),
                        Shelf.Lists.Items(
                            id = "top_playlists",
                            title = "Top Playlists",
                            list = topPlaylists
                        )
                    )
                }
                "browse" -> {
                    val playlists = api.getChartPlaylists()
                    val albums = api.getChartAlbums()
                    listOf(
                        Shelf.Lists.Items(
                            id = "browse_playlists",
                            title = "Featured Playlists",
                            list = playlists
                        ),
                        Shelf.Lists.Items(
                            id = "browse_albums",
                            title = "Top Albums",
                            list = albums
                        )
                    )
                }
                else -> {
                    val trending = api.getDiscoverTrending()
                    val artists = api.getDiscoverArtists()
                    val recent = api.getDiscoverRecent()
                    listOf(
                        Shelf.Lists.Tracks(
                            id = "trending_now",
                            title = "Trending Now",
                            list = trending
                        ),
                        Shelf.Lists.Items(
                            id = "artists_for_you",
                            title = "Artists For You",
                            list = artists
                        ),
                        Shelf.Lists.Tracks(
                            id = "recent_releases",
                            title = "Recent Releases",
                            list = recent
                        )
                    )
                }
            }
            shelves.toFeedData()
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return api.getTrackDetail(track.id) ?: track
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val streamingUrl = streamable.extras["streaming_url"]
        if (!streamingUrl.isNullOrBlank()) {
            return streamingUrl.toServerMedia(
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Referer" to "https://audiomack.com/"
                )
            )
        }

        val artistSlug = streamable.extras["artist_slug"]
        val urlSlug = streamable.extras["url_slug"]
        val fullStreamUrl = api.getStreamUrl(
            trackId = streamable.id,
            artistSlug = artistSlug,
            urlSlug = urlSlug
        ) ?: throw Exception("Could not retrieve full stream URL for track: ${streamable.id}")
        return fullStreamUrl.toServerMedia(
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Referer" to "https://audiomack.com/"
            )
        )
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        return api.getQuickSearch(query)
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // Audiomack does not store search history on server
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val tabs = listOf(
            Tab(id = "all", title = "All"),
            Tab(id = "songs", title = "Songs"),
            Tab(id = "playlists", title = "Playlists"),
            Tab(id = "artists", title = "Artists"),
            Tab(id = "albums", title = "Albums")
        )

        return Feed(tabs) { tab ->
            val tabId = tab?.id ?: "all"
            val shelves: List<Shelf> = when (tabId) {
                "playlists" -> {
                    val playlists = api.searchPlaylists(query, 1)
                    listOf(
                        Shelf.Lists.Items(
                            id = "search_playlists",
                            title = "Playlists",
                            list = playlists,
                            type = Shelf.Lists.Type.Grid
                        )
                    )
                }
                "artists" -> {
                    val artists = api.searchArtists(query, 1)
                    listOf(
                        Shelf.Lists.Items(
                            id = "search_artists",
                            title = "Artists",
                            list = artists,
                            type = Shelf.Lists.Type.Grid
                        )
                    )
                }
                "songs" -> {
                    val songs = api.searchSongs(query, 1)
                    listOf(
                        Shelf.Lists.Tracks(
                            id = "search_songs",
                            title = "Songs",
                            list = songs,
                            type = Shelf.Lists.Type.Grid
                        )
                    )
                }
                "albums" -> {
                    val albums = api.searchAlbums(query, 1)
                    listOf(
                        Shelf.Lists.Items(
                            id = "search_albums",
                            title = "Albums",
                            list = albums,
                            type = Shelf.Lists.Type.Grid
                        )
                    )
                }
                else -> {
                    coroutineScope {
                        val artistsDeferred = async { api.searchArtists(query, 1) }
                        val songsDeferred = async { api.searchSongs(query, 1) }
                        val albumsDeferred = async { api.searchAlbums(query, 1) }
                        val playlistsDeferred = async { api.searchPlaylists(query, 1) }

                        val artists = artistsDeferred.await()
                        val songs = songsDeferred.await()
                        val albums = albumsDeferred.await()
                        val playlists = playlistsDeferred.await()

                        val resultShelves = mutableListOf<Shelf>()
                        if (songs.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Tracks(
                                    id = "search_all_songs",
                                    title = "Songs",
                                    list = songs
                                )
                            )
                        }
                        if (albums.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_albums",
                                    title = "Albums",
                                    list = albums
                                )
                            )
                        }
                        if (artists.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Items(
                                    id = "search_accounts",
                                    title = "Artists",
                                    list = artists.take(5)
                                )
                            )
                        }
                        if (playlists.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_playlists",
                                    title = "Playlists",
                                    list = playlists
                                )
                            )
                        }
                        resultShelves
                    }
                }
            }
            shelves.toFeedData()
        }
    }
}
