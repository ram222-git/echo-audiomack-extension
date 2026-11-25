package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditPrivacyClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
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
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Audiomack music extension for Echo.
 *
 * All data comes from the OAuth 1.0a v1 API used by the official Android app. Website RSC
 * scraping has been removed from the catalog and playback paths.
 */
class AudiomackExtension :
    ExtensionClient,
    LoginClient.CustomInput,
    HomeFeedClient,
    TrackClient,
    QuickSearchClient,
    AlbumClient,
    PlaylistClient,
    ArtistClient,
    LibraryFeedClient,
    LikeClient,
    SaveClient,
    FollowClient,
    PlaylistEditPrivacyClient {

    private lateinit var settings: Settings

    private val sessions by lazy {
        AudiomackSessionManager { if (::settings.isInitialized) settings else null }
    }
    private val api by lazy {
        AudiomackApiClient(
            sessions = sessions,
            consumerKey = {
                settings.getString("consumer_key")?.takeIf { it.isNotBlank() }
                    ?: AudiomackOAuth.DEFAULT_CONSUMER_KEY
            },
            consumerSecret = {
                settings.getString("consumer_secret")?.takeIf { it.isNotBlank() }
                    ?: AudiomackOAuth.DEFAULT_CONSUMER_SECRET
            }
        )
    }
    private val auth by lazy { AudiomackAuthManager(api, sessions) }
    private val mapper by lazy { AudiomackMetadataMapper { auth.currentUser() != null } }
    private val catalog by lazy { AudiomackCatalogService(api, mapper) }
    private val search by lazy { AudiomackSearchService(api, mapper) }
    private val artists by lazy { AudiomackArtistService(api, mapper) }
    private val albums by lazy { AudiomackAlbumService(api, mapper) }
    private val playlists by lazy { AudiomackPlaylistService(api, mapper) }
    private val library by lazy { AudiomackLibraryService(api, mapper, auth, playlists) }
    private val streamResolver by lazy { AudiomackStreamResolver(api) }
    private val playback by lazy { AudiomackPlaybackService(streamResolver) }

    private fun preferHq(): Boolean =
        if (::settings.isInitialized) settings.getBoolean("prefer_hq") ?: true else true

    // ---------------------------------------------------------------- settings & lifecycle

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            title = "Prefer high-quality stream",
            key = "prefer_hq",
            summary = "Request Audiomack's HQ m4a stream when the account is allowed to play it.",
            defaultValue = true
        ),
        SettingTextInput(
            title = "Audiomack consumer key",
            key = "consumer_key",
            summary = "Optional. Leave empty to use the official app's public consumer.",
            defaultValue = ""
        ),
        SettingTextInput(
            title = "Audiomack consumer secret",
            key = "consumer_secret",
            summary = "Optional. Leave empty to use the official app's public consumer.",
            defaultValue = ""
        )
    )

    override suspend fun onInitialize() {
        // Touch the lazy session so a persisted token is validated on startup.
        sessions.load()
    }

    override suspend fun onExtensionSelected() {
        sessions.load()
    }

    // ---------------------------------------------------------------- login

    override val forms: List<LoginClient.Form> = listOf(
        LoginClient.Form(
            key = "credentials",
            label = "Sign in with Audiomack",
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Email,
                    key = "email",
                    label = "Email or username",
                    isRequired = true
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "password",
                    label = "Password",
                    isRequired = true
                )
            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        if (key != "credentials") {
            throw ClientException.NotSupported("Unknown Audiomack login form: $key")
        }
        val email = data["email"]?.trim().orEmpty()
        val password = data["password"].orEmpty()
        val user = auth.login(email, password)
        library.invalidateAll()
        return listOf(user)
    }

    override fun setLoginUser(user: User?) {
        auth.setActiveUser(user)
        library.invalidateAll()
    }

    override suspend fun getCurrentUser(): User? = auth.currentUser()

    // ---------------------------------------------------------------- home

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab(id = "discover", title = "Discover"),
            Tab(id = "charts", title = "Charts"),
            Tab(id = "genres", title = "Genres")
        )
        return Feed(tabs) { tab ->
            val shelves = when (tab?.id ?: "discover") {
                "charts" -> chartShelves()
                "genres" -> genreShelves()
                else -> discoverShelves()
            }
            shelves.toFeedData(
                buttons = Feed.Buttons(showSearch = true, showSort = false, showPlayAndShuffle = false)
            )
        }
    }

    private suspend fun discoverShelves(): List<Shelf> {
        val trending = catalog.trending(1)
        val recent = catalog.recent(1)
        val categories = catalog.categories()
        val featured = categories.firstOrNull()?.let { catalog.playlistsForCategory(it.slug, 1) }.orEmpty()

        return listOfNotNull(
            Shelf.Lists.Tracks(
                id = "discover_trending",
                title = "Trending Now",
                list = trending.data,
                more = moreTracks { page -> catalog.trending(page) }
            ).takeIf { it.list.isNotEmpty() },
            Shelf.Lists.Tracks(
                id = "discover_recent",
                title = "New Releases",
                list = recent.data,
                more = moreTracks { page -> catalog.recent(page) }
            ).takeIf { it.list.isNotEmpty() },
            Shelf.Lists.Items(
                id = "discover_playlists",
                title = "Featured Playlists",
                list = featured,
                more = moreItems { page ->
                    categories.firstOrNull()?.let { catalog.playlistsForCategory(it.slug, page) }.orEmpty()
                }
            ).takeIf { it.list.isNotEmpty() },
            categoriesShelf(categories).takeIf { categories.isNotEmpty() }
        )
    }

    private suspend fun chartShelves(): List<Shelf> {
        val categories = catalog.categories()
        val topCategory = categories.firstOrNull { it.slug.contains("week", true) } ?: categories.firstOrNull()
        val playlists = topCategory?.let { catalog.playlistsForCategory(it.slug, 1) }.orEmpty()
        val trending = catalog.trending(1)
        val recent = catalog.recent(1)

        return listOfNotNull(
            Shelf.Lists.Tracks(
                id = "chart_top_songs",
                title = "Top Songs",
                list = trending.data,
                more = moreTracks { page -> catalog.trending(page) }
            ).takeIf { it.list.isNotEmpty() },
            Shelf.Lists.Tracks(
                id = "chart_new_releases",
                title = "Fresh Uploads",
                list = recent.data,
                more = moreTracks { page -> catalog.recent(page) }
            ).takeIf { it.list.isNotEmpty() },
            Shelf.Lists.Items(
                id = "chart_playlists",
                title = topCategory?.let { "Top Playlists • ${it.title}" } ?: "Top Playlists",
                list = playlists,
                more = moreItems { page ->
                    topCategory?.let { catalog.playlistsForCategory(it.slug, page) }.orEmpty()
                }
            ).takeIf { it.list.isNotEmpty() }
        )
    }

    private suspend fun genreShelves(): List<Shelf> {
        val categories = catalog.categories()
        if (categories.isEmpty()) return emptyList()
        return listOf(categoriesShelf(categories))
    }

    private fun categoriesShelf(categories: List<AudiomackCatalogService.Category>): Shelf =
        Shelf.Lists.Categories(
            id = "genres",
            title = "Genres & Collections",
            list = categories.map { category ->
                Shelf.Category(
                    id = "genre_${category.slug}",
                    title = category.title,
                    image = category.image,
                    feed = Feed(emptyList()) { _ ->
                        val data = PagedData.Continuous<Shelf> { continuation ->
                            val page = continuation?.toIntOrNull() ?: 1
                            val raw = catalog.playlistsForCategory(category.slug, page)
                            Page(raw.map { Shelf.Item(it) }, if (raw.size >= 20) (page + 1).toString() else null)
                        }
                        data.toFeedData(buttons = Feed.Buttons.EMPTY)
                    }
                )
            },
            type = Shelf.Lists.Type.Grid
        )

    private fun moreTracks(load: suspend (Int) -> Page<Track>): Feed<Shelf> =
        Feed(emptyList()) { _ ->
            PagedData.Continuous<Shelf> { continuation ->
                val pageNumber = continuation?.toIntOrNull() ?: 1
                val page = load(pageNumber)
                Page(page.data.map { Shelf.Item(it) }, page.continuation)
            }.toFeedData(
                buttons = Feed.Buttons(
                    showSearch = true,
                    showSort = false,
                    showPlayAndShuffle = true
                )
            )
        }

    private fun moreItems(load: suspend (Int) -> List<EchoMediaItem>): Feed<Shelf> =
        Feed(emptyList()) { _ ->
            PagedData.Continuous<Shelf> { continuation ->
                val pageNumber = continuation?.toIntOrNull() ?: 1
                val items = load(pageNumber)
                Page(items.map { Shelf.Item(it) }, if (items.size >= 20) (pageNumber + 1).toString() else null)
            }.toFeedData(buttons = Feed.Buttons(showSearch = true, showSort = false))
        }

    // ---------------------------------------------------------------- track & playback

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val json = api.getTrack(
            trackId = track.id,
            artistSlug = track.extras["artist_slug"],
            urlSlug = track.extras["url_slug"]
        ) ?: throw AudiomackErrorHandler.invalidResponse(
            "This track is no longer available on Audiomack."
        )
        // Some feeds expose an album/release row with the album id. Resolve it to the
        // matching song inside the release so playback never targets an album id.
        if (json.string("type") == "album") {
            val albumTracks = json.array("tracks")
                ?: kotlinx.serialization.json.JsonArray(emptyList())
            val entries = albumTracks.mapIndexedNotNull { index, element ->
                (element as? kotlinx.serialization.json.JsonObject)?.let { index to it }
            }
            val chosen = entries.firstOrNull { (_, obj) ->
                obj.string("title").equals(track.title, ignoreCase = true)
            } ?: entries.firstOrNull { (_, obj) -> obj.string("song_id").isNotBlank() }
                ?: throw AudiomackErrorHandler.invalidResponse(
                    "This Audiomack release has no playable song."
                )
            val (index, song) = chosen
            val fromAlbum = mapper.parseTrack(
                song,
                albumTitle = json.string("title"),
                albumCover = json.stringOrNull("image"),
                albumId = json.string("id"),
                albumOrder = (index + 1).toLong()
            )
            if (fromAlbum.id.isBlank() || fromAlbum.streamables.isEmpty()) {
                throw AudiomackErrorHandler.invalidResponse(
                    "This Audiomack release has no playable song."
                )
            }
            return fromAlbum.copy(
                cover = fromAlbum.cover ?: track.cover,
                album = fromAlbum.album ?: track.album,
                extras = track.extras + fromAlbum.extras
            )
        }

        val parsed = mapper.parseTrack(json)
        if (parsed.id.isBlank()) {
            throw AudiomackErrorHandler.invalidResponse("Audiomack returned incomplete track data.")
        }
        return parsed.copy(
            cover = parsed.cover ?: track.cover,
            album = parsed.album ?: track.album,
            duration = parsed.duration ?: track.duration,
            extras = track.extras + parsed.extras
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media = playback.loadMedia(streamable, preferHq())

    override suspend fun loadFeed(track: Track): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val artistSlug = try {
            artists.resolveKey(
                track.artists.firstOrNull() ?: Artist(id = "", name = track.title)
            )
        } catch (e: AudiomackException) {
            track.extras["artist_slug"].orEmpty()
        }

        val related = try {
            api.getRelatedTracks(track.id).mapNotNull { obj ->
                runCatching { mapper.parseTrack(obj) }.getOrNull()
            }.filter { it.id != track.id }
        } catch (e: AudiomackException) {
            if (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 404) emptyList() else throw e
        } catch (e: ClientException.LoginRequired) {
            // Related-track recommendations are account-only.
            emptyList()
        }

        if (related.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Tracks(
                    id = "track_related",
                    title = "Related tracks",
                    list = related,
                    more = moreTracks { page ->
                        val raw = api.getRelatedTracks(track.id)
                        val pageData = raw.map { mapper.parseTrack(it) }
                        Page(pageData, null)
                    }
                )
            )
        }

        if (artistSlug.isNotBlank()) {
            val artistName = track.artists.firstOrNull()?.name ?: "Artist"
            val firstPage = runCatching {
                artists.uploadsPage(Artist(id = artistSlug, name = artistName), 1)
            }.getOrNull()
            if (firstPage != null && firstPage.data.isNotEmpty()) {
                shelves.add(
                    Shelf.Lists.Tracks(
                        id = "track_more_from_artist",
                        title = "More from $artistName",
                        list = firstPage.data.filter { it.id != track.id }.take(10),
                        more = moreTracks { page ->
                            artists.uploadsPage(Artist(id = artistSlug, name = artistName), page)
                        }
                    )
                )
            }
        }
        return Feed(emptyList()) { shelves.toFeedData(buttons = Feed.Buttons(showSearch = true, showSort = false)) }
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<QuickSearchItem> = search.quickSearch(query)

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // Audiomack has no server-side search history; Echo owns local history.
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val tabs = listOf(
            Tab(id = "all", title = "All"),
            Tab(id = "songs", title = "Songs"),
            Tab(id = "albums", title = "Albums"),
            Tab(id = "artists", title = "Artists"),
            Tab(id = "playlists", title = "Playlists")
        )
        return Feed(tabs) { tab ->
            val id = tab?.id ?: "all"
            val data = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                val result = if (id == "all") {
                    search.searchAll(query, page)
                } else {
                    search.search(query, id, page)
                }
                Page(result.data.map { Shelf.Item(it) }, result.continuation)
            }
            data.toFeedData(
                buttons = Feed.Buttons(
                    showSearch = true,
                    showSort = false,
                    showPlayAndShuffle = id == "songs" || id == "all"
                )
            )
        }
    }

    // ---------------------------------------------------------------- album

    override suspend fun loadAlbum(album: Album): Album = albums.load(album)

    override suspend fun loadTracks(album: Album): Feed<Track>? = albums.loadTracks(album).toFeed()

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ---------------------------------------------------------------- playlist

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlists.load(playlist)

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> =
        playlists.loadTracks(playlist).toFeed()

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> =
        library.editablePlaylists(track)

    override suspend fun createPlaylist(title: String, description: String?): Playlist =
        library.createPlaylist(title, description)

    override suspend fun deletePlaylist(playlist: Playlist) = library.deletePlaylist(playlist)

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) =
        library.editPlaylistMetadata(playlist, title, description)

    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>
    ) {
        if (index < tracks.size) {
            throw ClientException.NotSupported(
                "Audiomack only appends tracks; inserting at a specific position is not supported."
            )
        }
        library.addTracks(playlist, new)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>
    ) = library.removeTracks(playlist, tracks, indexes)

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int
    ): Unit = throw ClientException.NotSupported(
        "Audiomack's API does not expose playlist track reordering."
    )

    override suspend fun setPrivacy(playlist: Playlist, isPrivate: Boolean) =
        library.setPrivacy(playlist, isPrivate)

    // ---------------------------------------------------------------- artist

    override suspend fun loadArtist(artist: Artist): Artist = artists.load(artist)

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val data = artists.loadData(artist)
        val shelves = mutableListOf<Shelf>()

        if (data.pinned.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "artist_pinned",
                    title = "Featured",
                    list = data.pinned
                )
            )
        }
        if (data.topSongs.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Tracks(
                    id = "artist_top_songs",
                    title = "Top songs",
                    list = data.topSongs,
                    more = moreTracks { page -> artists.uploadsPage(data.artist, page) }
                )
            )
        }
        if (data.albums.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "artist_albums",
                    title = "Albums",
                    list = data.albums
                )
            )
        }
        if (data.playlists.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "artist_playlists",
                    title = "Playlists",
                    list = data.playlists
                )
            )
        }
        if (data.related.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "artist_related",
                    title = "Related artists",
                    list = data.related
                )
            )
        }

        return Feed(emptyList()) {
            shelves.toFeedData(
                buttons = Feed.Buttons(
                    showSearch = true,
                    showSort = false,
                    showPlayAndShuffle = data.topSongs.isNotEmpty(),
                    customTrackList = data.topSongs
                )
            )
        }
    }

    // ---------------------------------------------------------------- library

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        if (auth.currentUser() == null) throw ClientException.LoginRequired()
        val tabs = listOf(
            Tab(id = "recent", title = "Recently played"),
            Tab(id = "liked", title = "Liked tracks"),
            Tab(id = "albums", title = "Saved albums"),
            Tab(id = "playlists", title = "My playlists"),
            Tab(id = "following", title = "Following")
        )
        return Feed(tabs) { tab ->
            val shelves = when (tab?.id ?: "recent") {
                "liked" -> {
                    val page = library.likedTracks(1)
                    listOfNotNull(
                        Shelf.Lists.Tracks(
                            id = "library_liked",
                            title = "Liked tracks",
                            list = page.data,
                            more = moreTracks { p -> library.likedTracks(p) }
                        ).takeIf { it.list.isNotEmpty() }
                    )
                }
                "albums" -> {
                    val page = library.savedAlbums(1)
                    listOfNotNull(
                        Shelf.Lists.Items(
                            id = "library_albums",
                            title = "Saved albums",
                            list = page.data,
                            more = moreItems { p -> library.savedAlbums(p).data }
                        ).takeIf { it.list.isNotEmpty() }
                    )
                }
                "playlists" -> {
                    val page = library.myPlaylists(1)
                    listOfNotNull(
                        Shelf.Lists.Items(
                            id = "library_playlists",
                            title = "My playlists",
                            list = page.data,
                            more = moreItems { p -> library.myPlaylists(p).data }
                        ).takeIf { it.list.isNotEmpty() }
                    )
                }
                "following" -> listOfNotNull(
                    Shelf.Lists.Items(
                        id = "library_following",
                        title = "Artists you follow",
                        list = library.following()
                    ).takeIf { it.list.isNotEmpty() }
                )
                else -> {
                    val recent = library.recentlyPlayed()
                    listOfNotNull(
                        Shelf.Lists.Tracks(
                            id = "library_recent",
                            title = "Recently played",
                            list = recent
                        ).takeIf { it.list.isNotEmpty() }
                    )
                }
            }
            shelves.toFeedData(
                buttons = Feed.Buttons(showSearch = true, showSort = false, showPlayAndShuffle = true)
            )
        }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) =
        library.setFavorite(item, shouldLike)

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = library.isFavorite(item)

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) =
        library.setFavorite(item, shouldSave)

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = library.isFavorite(item)

    // ---------------------------------------------------------------- follow

    override suspend fun isFollowing(item: EchoMediaItem): Boolean = library.isFollowing(item)

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? =
        library.followersCount(item)

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) =
        library.setFollowing(item, shouldFollow)
}
