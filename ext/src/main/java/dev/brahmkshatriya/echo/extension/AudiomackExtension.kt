package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Playlist
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

class AudiomackExtension : ExtensionClient, HomeFeedClient, TrackClient, QuickSearchClient, AlbumClient, PlaylistClient, ArtistClient {

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
                            list = topSongs,
                            more = Feed(emptyList()) { topSongs.map { Shelf.Item(it) }.toFeedData() }
                        ),
                        Shelf.Lists.Items(
                            id = "top_albums",
                            title = "Top Albums",
                            list = topAlbums,
                            more = Feed(emptyList()) { topAlbums.map { Shelf.Item(it) }.toFeedData() }
                        ),
                        Shelf.Lists.Items(
                            id = "top_playlists",
                            title = "Top Playlists",
                            list = topPlaylists,
                            more = Feed(emptyList()) { topPlaylists.map { Shelf.Item(it) }.toFeedData() }
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
                            list = playlists,
                            more = Feed(emptyList()) { playlists.map { Shelf.Item(it) }.toFeedData() }
                        ),
                        Shelf.Lists.Items(
                            id = "browse_albums",
                            title = "Top Albums",
                            list = albums,
                            more = Feed(emptyList()) { albums.map { Shelf.Item(it) }.toFeedData() }
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
                            list = trending,
                            more = Feed(emptyList()) { trending.map { Shelf.Item(it) }.toFeedData() }
                        ),
                        Shelf.Lists.Items(
                            id = "artists_for_you",
                            title = "Artists For You",
                            list = artists,
                            more = Feed(emptyList()) { artists.map { Shelf.Item(it) }.toFeedData() }
                        ),
                        Shelf.Lists.Tracks(
                            id = "recent_releases",
                            title = "Recent Releases",
                            list = recent,
                            more = Feed(emptyList()) { recent.map { Shelf.Item(it) }.toFeedData() }
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

    override suspend fun loadFeed(track: Track): Feed<Shelf> {
        val artist = track.artists.firstOrNull()
        val artistSlug = track.extras["artist_slug"]
            ?: (if (artist != null) api.resolveOfficialArtistSlug(artist) else "")
        val songSlug = track.extras["url_slug"]

        if (artistSlug.isBlank()) return Feed(emptyList()) { emptyList<Shelf>().toFeedData() }

        val feedData = api.getTrackFeedData(artistSlug, songSlug, track.id)
        val shelves = mutableListOf<Shelf>()
        val artistName = artist?.name ?: "Artist"

        if (feedData.moreFromArtist.isNotEmpty()) {
            val moreSongsFeed = Feed<Shelf>(emptyList()) { _ ->
                val pagedData = PagedData.Continuous<Shelf> { continuation ->
                    val pageNum = continuation?.toIntOrNull() ?: 1
                    val pageSongs = api.getArtistUploads(artistSlug, page = pageNum, limit = 50)
                    val nextCont = if (pageSongs.size >= 50) (pageNum + 1).toString() else null
                    Page(
                        pageSongs.map { Shelf.Item(it) },
                        nextCont
                    )
                }
                pagedData.toFeedData(
                    buttons = Feed.Buttons(
                        showSearch = true,
                        showSort = false,
                        showPlayAndShuffle = true
                    )
                )
            }

            shelves.add(
                Shelf.Lists.Tracks(
                    id = "track_more_from_artist",
                    title = "More from $artistName",
                    list = feedData.moreFromArtist,
                    type = Shelf.Lists.Type.Linear,
                    more = moreSongsFeed
                )
            )
        }

        if (feedData.playlistsFeaturing.isNotEmpty()) {
            val morePlaylistsFeed = Feed<Shelf>(emptyList()) { _ ->
                feedData.playlistsFeaturing.map { Shelf.Item(it) }.toFeedData()
            }

            shelves.add(
                Shelf.Lists.Items(
                    id = "track_playlists_featuring",
                    title = "Playlists featuring artist",
                    list = feedData.playlistsFeaturing,
                    type = Shelf.Lists.Type.Linear,
                    more = morePlaylistsFeed
                )
            )
        }

        return Feed(emptyList()) { shelves.toFeedData() }
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
                    playlists.map { Shelf.Item(it) }
                }
                "artists" -> {
                    val artists = api.searchArtists(query, 1)
                    artists.map { Shelf.Item(it) }
                }
                "songs" -> {
                    val songs = api.searchSongs(query, 1)
                    songs.map { Shelf.Item(it) }
                }
                "albums" -> {
                    val albums = api.searchAlbums(query, 1)
                    albums.map { Shelf.Item(it) }
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
                                    list = songs,
                                    more = Feed(emptyList()) { songs.map { Shelf.Item(it) }.toFeedData() }
                                )
                            )
                        }
                        if (albums.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_albums",
                                    title = "Albums",
                                    list = albums,
                                    more = Feed(emptyList()) { albums.map { Shelf.Item(it) }.toFeedData() }
                                )
                            )
                        }
                        if (artists.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Items(
                                    id = "search_accounts",
                                    title = "Artists",
                                    list = artists.take(5),
                                    more = Feed(emptyList()) { artists.map { Shelf.Item(it) }.toFeedData() }
                                )
                            )
                        }
                        if (playlists.isNotEmpty()) {
                            resultShelves.add(
                                Shelf.Lists.Items(
                                    id = "search_all_playlists",
                                    title = "Playlists",
                                    list = playlists,
                                    more = Feed(emptyList()) { playlists.map { Shelf.Item(it) }.toFeedData() }
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

    // AlbumClient Implementation
    override suspend fun loadAlbum(album: Album): Album {
        val artistSlug = album.extras["artist_slug"]
        val urlSlug = album.extras["url_slug"]
        val (detail, _) = api.getAlbumDetail(album.id, artistSlug, urlSlug) ?: return album
        return detail
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val artistSlug = album.extras["artist_slug"]
        val urlSlug = album.extras["url_slug"]
        val (_, tracks) = api.getAlbumDetail(album.id, artistSlug, urlSlug) ?: return null
        return tracks.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }

    // PlaylistClient Implementation
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val artistSlug = playlist.extras["artist_slug"]
        val urlSlug = playlist.extras["url_slug"]
        val (detail, _) = api.getPlaylistDetail(playlist.id, artistSlug, urlSlug) ?: return playlist
        return detail
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val artistSlug = playlist.extras["artist_slug"]
        val urlSlug = playlist.extras["url_slug"]
        val (_, tracks) = api.getPlaylistDetail(playlist.id, artistSlug, urlSlug) ?: return emptyList<Track>().toFeed()
        return tracks.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        return null
    }

    // ArtistClient Implementation
    override suspend fun loadArtist(artist: Artist): Artist {
        val officialSlug = api.resolveOfficialArtistSlug(artist)
        val pageData = api.getArtistPageData(officialSlug)
        if (pageData != null) {
            val a = pageData.artist
            return artist.copy(
                id = a.id,
                name = a.name.ifBlank { artist.name },
                cover = a.cover ?: artist.cover,
                bio = a.bio ?: artist.bio,
                background = a.background ?: a.cover ?: artist.background,
                subtitle = a.subtitle ?: artist.subtitle,
                extras = (if (a.extras.isNotEmpty()) a.extras else artist.extras) + mapOf("url_slug" to officialSlug)
            )
        }
        return artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val artistSlug = api.resolveOfficialArtistSlug(artist)
        val pageData = api.getArtistPageData(artistSlug)

        return Feed(emptyList()) { _ ->
            val list = mutableListOf<Shelf>()

            pageData?.highlights?.takeIf { it.isNotEmpty() }?.let { items ->
                list.add(
                    Shelf.Lists.Items(
                        id = "artist_highlighted",
                        title = "Highlighted",
                        list = items,
                        type = Shelf.Lists.Type.Linear
                    )
                )
            }

            pageData?.topSongs?.takeIf { it.isNotEmpty() }?.let { songs ->
                val moreSongsFeed = Feed<Shelf>(emptyList()) { _ ->
                    val pagedData = PagedData.Continuous<Shelf> { continuation ->
                        val pageNum = continuation?.toIntOrNull() ?: 1
                        val pageSongs = api.getArtistUploads(artistSlug, page = pageNum, limit = 50)
                        val nextCont = if (pageSongs.size >= 50) (pageNum + 1).toString() else null
                        Page(
                            pageSongs.map { Shelf.Item(it) },
                            nextCont
                        )
                    }
                    pagedData.toFeedData(
                        buttons = Feed.Buttons(
                            showSearch = true,
                            showSort = false,
                            showPlayAndShuffle = true
                        )
                    )
                }

                list.add(
                    Shelf.Lists.Tracks(
                        id = "artist_top_songs",
                        title = "Top songs",
                        list = songs,
                        type = Shelf.Lists.Type.Linear,
                        more = moreSongsFeed
                    )
                )
            }

            pageData?.topAlbums?.takeIf { it.isNotEmpty() }?.let { albums ->
                val moreAlbumsFeed = Feed<Shelf>(emptyList()) { _ ->
                    albums.map { Shelf.Item(it) }.toFeedData()
                }

                list.add(
                    Shelf.Lists.Items(
                        id = "artist_recent_albums",
                        title = "Albums",
                        list = albums,
                        type = Shelf.Lists.Type.Linear,
                        more = moreAlbumsFeed
                    )
                )
            }

            val featuring = pageData?.playlistsFeaturing.orEmpty()
            if (featuring.isNotEmpty()) {
                val moreFeaturingFeed = Feed<Shelf>(emptyList()) { _ ->
                    featuring.map { Shelf.Item(it) }.toFeedData()
                }

                list.add(
                    Shelf.Lists.Items(
                        id = "artist_playlists_featuring",
                        title = "Playlists featuring artist",
                        list = featuring,
                        type = Shelf.Lists.Type.Linear,
                        more = moreFeaturingFeed
                    )
                )
            }

            val ownPlaylists = pageData?.playlists.orEmpty()
            if (ownPlaylists.isNotEmpty()) {
                val morePlaylistsFeed = Feed<Shelf>(emptyList()) { _ ->
                    ownPlaylists.map { Shelf.Item(it) }.toFeedData()
                }

                list.add(
                    Shelf.Lists.Items(
                        id = "artist_playlists",
                        title = "Playlists",
                        list = ownPlaylists,
                        type = Shelf.Lists.Type.Linear,
                        more = morePlaylistsFeed
                    )
                )
            }

            list.toFeedData(
                buttons = Feed.Buttons(
                    showSearch = true,
                    showSort = false,
                    showPlayAndShuffle = pageData?.topSongs?.isNotEmpty() == true,
                    customTrackList = pageData?.topSongs
                )
            )
        }
    }
}
