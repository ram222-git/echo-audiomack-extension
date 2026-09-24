package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = AudiomackExtension()
    private val api = AudiomackApi()
    private val user = User("", "Test User")

    @Test
    fun testHomeFeedDiscover() = testIn("Testing Home Feed Discover") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()
        println("Tabs: " + feed.tabs.map { it.title })
        assert(feed.tabs.isNotEmpty())

        val discoverShelves = feed.getPagedData(Tab("discover", "Discover")).pagedData.loadPage(null).data
        println("=== DISCOVER SHELVES ===")
        discoverShelves.forEach { shelf ->
            val count = when (shelf) {
                is Shelf.Lists.Tracks -> shelf.list.size
                is Shelf.Lists.Items -> shelf.list.size
                else -> 0
            }
            println("Shelf: ${shelf.id} ('${shelf.title}') -> items: $count")
        }
        assert(discoverShelves.isNotEmpty())
    }

    @Test
    fun testHomeFeedCharts() = testIn("Testing Home Feed Charts") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()

        val chartShelves = feed.getPagedData(Tab("charts", "Charts")).pagedData.loadPage(null).data
        println("=== CHARTS SHELVES ===")
        chartShelves.forEach { shelf ->
            val count = when (shelf) {
                is Shelf.Lists.Tracks -> shelf.list.size
                is Shelf.Lists.Items -> shelf.list.size
                else -> 0
            }
            println("Shelf: ${shelf.id} ('${shelf.title}') -> items: $count")
        }
        assert(chartShelves.isNotEmpty())
    }

    @Test
    fun testLoadTrackDetailAndDuration() = testIn("Testing Track Detail and Duration") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val initialTrack = Track(id = "104665031", title = "Dekhi Match")
        val loadedTrack = extension.loadTrack(initialTrack, false)
        println("Loaded Track: ${loadedTrack.title}")
        println("Artists (${loadedTrack.artists.size}): ${loadedTrack.artists.map { "'${it.name}' (id: ${it.id})" }}")
        println("Duration: ${loadedTrack.duration} ms (${(loadedTrack.duration ?: 0) / 1000} seconds)")
        assert(loadedTrack.duration != null && loadedTrack.duration!! > 30000L) {
            "Track duration is null or shorter than 30s: ${loadedTrack.duration}"
        }
        assert(loadedTrack.artists.size >= 2) {
            "Expected multiple artists for Dekhi Match, got ${loadedTrack.artists.size}"
        }
        loadedTrack.artists.forEach {
            assert(!it.name.contains(",")) { "Artist name should not contain comma: ${it.name}" }
        }
    }

    @Test
    fun testFullSongStream() = testIn("Testing Full Song Stream Resolution") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val trackId = "104665031" // Dekhi Match
        val streamable = Streamable.server(
            id = trackId,
            quality = 128,
            title = "Audiomack Audio"
        )
        val media = extension.loadStreamableMedia(streamable, false)
        println("Loaded Streamable Media: $media")
        when (media) {
            is Streamable.Media.Server -> {
                val source = media.sources.firstOrNull() as? Streamable.Source.Http
                println("Stream Source URL: ${source?.request?.url}")
                assert(source != null && source.request.url.contains("music.audiomack.com")) {
                    "URL does not point to music.audiomack.com"
                }
            }
            else -> error("Unexpected media type: $media")
        }
    }

    @Test
    fun testQuickSearchSuggestions() = testIn("Testing Quick Search Suggestions") {
        if (extension !is QuickSearchClient) error("QuickSearchClient is not implemented")
        val suggestions = extension.quickSearch("Sidhu Moose Wala")
        println("Quick Search items count: ${suggestions.size}")
        assert(suggestions.isNotEmpty()) { "QuickSearch returned no items" }

        val querySuggestions = suggestions.filterIsInstance<QuickSearchItem.Query>()
        val mediaSuggestions = suggestions.filterIsInstance<QuickSearchItem.Media>()

        println(" - Query suggestions: ${querySuggestions.map { it.query }}")
        println(" - Media suggestions: ${mediaSuggestions.map { "${it.media.title} (${it.media::class.simpleName})" }}")

        assert(querySuggestions.isNotEmpty() || mediaSuggestions.isNotEmpty())
    }

    @Test
    fun testSearchFeedWithTabs() = testIn("Testing Search Feed with All 5 Tabs") {
        if (extension !is QuickSearchClient) error("QuickSearchClient is not implemented")
        val feed = extension.loadSearchFeed("Sidhu Moose Wala")
        val expectedTabs = listOf("all", "songs", "playlists", "artists", "albums")
        println("Feed tabs: ${feed.tabs.map { "${it.title} (${it.id})" }}")

        assert(feed.tabs.map { it.id } == expectedTabs) {
            "Expected tabs $expectedTabs but got ${feed.tabs.map { it.id }}"
        }

        // 1. Test "all" tab
        val allShelves = feed.getPagedData(Tab("all", "All")).pagedData.loadPage(null).data
        println("=== 'all' tab shelves count: ${allShelves.size} ===")
        allShelves.forEach { shelf ->
            val count = when (shelf) {
                is Shelf.Lists.Tracks -> shelf.list.size
                is Shelf.Lists.Items -> shelf.list.size
                else -> 0
            }
            println(" - Shelf: [${shelf.id}] '${shelf.title}' -> $count items")
        }
        assert(allShelves.isNotEmpty()) { "'all' tab shelves were empty" }

        // 2. Test "playlists" tab
        val playlistShelves = feed.getPagedData(Tab("playlists", "Playlists")).pagedData.loadPage(null).data
        println("=== 'playlists' tab shelves count: ${playlistShelves.size} ===")
        assert(playlistShelves.isNotEmpty())

        // 3. Test "artists" tab
        val artistShelves = feed.getPagedData(Tab("artists", "Artists")).pagedData.loadPage(null).data
        println("=== 'artists' tab shelves count: ${artistShelves.size} ===")
        assert(artistShelves.isNotEmpty())

        // 4. Test "songs" tab
        val songShelves = feed.getPagedData(Tab("songs", "Songs")).pagedData.loadPage(null).data
        println("=== 'songs' tab shelves count: ${songShelves.size} ===")
        assert(songShelves.isNotEmpty())

        // 5. Test "albums" tab
        val albumShelves = feed.getPagedData(Tab("albums", "Albums")).pagedData.loadPage(null).data
        println("=== 'albums' tab shelves count: ${albumShelves.size} ===")
        assert(albumShelves.isNotEmpty())
    }

    @Test
    fun testLoadAlbumAndTracks() = testIn("Testing Load Album and Tracks") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")

        val albumUrl = "https://audiomack.com/himanshu-y/album/p-pop-culture-karan-aujla-ikky?_rsc=ramnz"
        val initialAlbum = Album(id = albumUrl, title = "")
        val loadedAlbum = extension.loadAlbum(initialAlbum)

        println("Loaded Album Title: ${loadedAlbum.title}")
        println("Loaded Album Artists: ${loadedAlbum.artists.map { it.name }}")
        println("Loaded Album TrackCount: ${loadedAlbum.trackCount}")
        println("Loaded Album Cover: ${loadedAlbum.cover}")

        assert(loadedAlbum.title.isNotBlank()) { "Album title should not be blank" }

        val tracksFeed = extension.loadTracks(loadedAlbum)
        assert(tracksFeed != null) { "Album tracks feed should not be null" }

        val tracks = tracksFeed!!.getPagedData(null).pagedData.loadPage(null).data
        println("Loaded Album Tracks count: ${tracks.size}")
        tracks.take(5).forEachIndexed { index, track ->
            println(" ${index + 1}. ${track.title} (${track.artists.map { it.name }}) - ${(track.duration ?: 0) / 1000}s [id: ${track.id}]")
        }

        assert(tracks.isNotEmpty()) { "Album tracks should not be empty" }
        assert(tracks.first().id.isNotBlank()) { "Album track ID should not be blank" }
        assert(tracks.first().duration != null && tracks.first().duration!! > 0) { "Album track duration should be valid" }
    }

    @Test
    fun testLoadPlaylistAndTracks() = testIn("Testing Load Playlist and Tracks") {
        if (extension !is PlaylistClient) error("PlaylistClient is not implemented")

        val playlistUrl = "https://audiomack.com/audiomack-desi/playlist/verified-punjabi?_rsc=ramnz"
        val initialPlaylist = Playlist(id = playlistUrl, title = "", isEditable = false)
        val loadedPlaylist = extension.loadPlaylist(initialPlaylist)

        println("Loaded Playlist Title: ${loadedPlaylist.title}")
        println("Loaded Playlist Authors: ${loadedPlaylist.authors.map { it.name }}")
        println("Loaded Playlist TrackCount: ${loadedPlaylist.trackCount}")
        println("Loaded Playlist Description: ${loadedPlaylist.description}")

        assert(loadedPlaylist.title.isNotBlank()) { "Playlist title should not be blank" }

        val tracksFeed = extension.loadTracks(loadedPlaylist)
        val tracks = tracksFeed.getPagedData(null).pagedData.loadPage(null).data
        println("Loaded Playlist Tracks count: ${tracks.size}")
        tracks.take(7).forEachIndexed { index, track ->
            println(" ${index + 1}. ${track.title} by ${track.artists.map { it.name }} - ${(track.duration ?: 0) / 1000}s [id: ${track.id}]")
        }

        assert(tracks.isNotEmpty()) { "Playlist tracks should not be empty" }
        assert(tracks.any { it.title.contains("Ghostface Killah", ignoreCase = true) }) {
            "Expected 'Ghostface Killah' in playlist tracks"
        }
    }

    @Test
    fun testLoadArtistAndFeed() = testIn("Testing Load Artist and Feed") {
        if (extension !is ArtistClient) error("ArtistClient is not implemented")

        val artistUrl = "https://audiomack.com/karanaujla?_rsc=ramnz"
        val initialArtist = Artist(id = artistUrl, name = "")
        val loadedArtist = extension.loadArtist(initialArtist)

        println("Loaded Artist Name: ${loadedArtist.name}")
        println("Loaded Artist Bio: ${loadedArtist.bio?.take(60)}...")
        println("Loaded Artist Subtitle: ${loadedArtist.subtitle}")

        assert(loadedArtist.name.contains("Karan Aujla", ignoreCase = true)) {
            "Artist name should be Karan Aujla"
        }
        assert(loadedArtist.cover != null) { "Artist cover should not be null" }

        val feed = extension.loadFeed(loadedArtist)
        println("Feed tabs: ${feed.tabs.map { "${it.title} (${it.id})" }}")
        assert(feed.tabs.isEmpty()) { "Tabs should be removed from artist page feed" }

        val shelves = feed.getPagedData(null).pagedData.loadPage(null).data
        println("=== Artist Shelves count: ${shelves.size} ===")
        shelves.forEach { shelf ->
            val (count, morePresent) = when (shelf) {
                is Shelf.Lists.Tracks -> Pair(shelf.list.size, shelf.more != null)
                is Shelf.Lists.Items -> Pair(shelf.list.size, shelf.more != null)
                else -> Pair(0, false)
            }
            println(" - Shelf: [${shelf.id}] '${shelf.title}' -> $count items (has more arrow: $morePresent)")
        }

        val topSongsShelf = shelves.filterIsInstance<Shelf.Lists.Tracks>().firstOrNull { it.id == "artist_top_songs" }
        assert(topSongsShelf != null) { "Top songs shelf should exist" }
        assert(topSongsShelf!!.more != null) { "Top songs shelf should have a 'more' feed for arrow click" }

        // Test clicking the arrow (more feed) for Top songs
        println("\n-- Testing Clicking Arrow (More feed) on Top songs --")
        val morePage = topSongsShelf.more!!.getPagedData(null).pagedData.loadPage(null)
        val moreShelves = morePage.data
        val firstPageItems = moreShelves.filterIsInstance<Shelf.Item>()
        println("More All Songs page 1: ${firstPageItems.size} items (1-row per song), continuation: ${morePage.continuation}")
        assert(firstPageItems.size >= 20) { "Expected all songs page to load at least 20 songs as Shelf.Item" }

        if (morePage.continuation != null) {
            val page2 = topSongsShelf.more!!.getPagedData(null).pagedData.loadPage(morePage.continuation)
            val secondPageItems = page2.data.filterIsInstance<Shelf.Item>()
            println("More All Songs page 2: ${secondPageItems.size} items (1-row per song), continuation: ${page2.continuation}")
            assert(secondPageItems.isNotEmpty()) { "Expected page 2 to load songs" }
        }
    }

    @Test
    fun testSearchIkky() = testIn("Testing Search Ikky and Verification") {
        if (extension !is ArtistClient) error("ArtistClient is not implemented")
        val secondaryArtist = Artist(id = "ikky", name = "Ikky")
        val resolvedArtist = extension.loadArtist(secondaryArtist)
        println("Resolved Artist Name: '${resolvedArtist.name}'")
        println("Resolved Artist ID/Slug: '${resolvedArtist.id}'")
        println("Resolved Artist Subtitle: '${resolvedArtist.subtitle}'")
        println("Resolved Artist Cover: '${resolvedArtist.cover}'")
        println("Resolved Artist Bio: '${resolvedArtist.bio}'")

        val slug = resolvedArtist.extras["url_slug"] ?: resolvedArtist.id
        assert(slug == "ikky-music") {
            "Expected Ikky to resolve to official slug 'ikky-music', got id='${resolvedArtist.id}', slug='$slug'"
        }
        assert(resolvedArtist.cover != null && !resolvedArtist.cover.toString().contains("default-artist-image")) {
            "Expected official artist image for Ikky"
        }

        val feed = extension.loadFeed(resolvedArtist)
        val shelves = feed.getPagedData(null).pagedData.loadPage(null).data
        println("Resolved Artist Shelves: ${shelves.map { it.title }}")
        assert(shelves.isNotEmpty()) { "Official artist feed should not be empty" }
    }

    @Test
    fun testSlashArtistSplitting() = testIn("Testing Slash Artist Splitting (e.g. raftaar/krsna)") {
        val artists1 = api.parseArtists("raftaar/krsna")
        println("Artists for 'raftaar/krsna': ${artists1.map { it.name }}")
        assert(artists1.size == 2) { "Expected 2 artists, got ${artists1.size}" }
        assert(artists1[0].name.equals("raftaar", ignoreCase = true))
        assert(artists1[1].name.equals("krsna", ignoreCase = true))

        val artists2 = api.parseArtists("raftaar / krsna")
        println("Artists for 'raftaar / krsna': ${artists2.map { it.name }}")
        assert(artists2.size == 2) { "Expected 2 artists, got ${artists2.size}" }
        assert(artists2[0].name.equals("raftaar", ignoreCase = true))
        assert(artists2[1].name.equals("krsna", ignoreCase = true))
    }

    @Test
    fun testTrackFeed() = testIn("Testing Track Info Feed (More from Artist & Playlists Featuring)") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val track = Track(
            id = "47317203",
            title = "Wavy",
            artists = listOf(Artist(id = "karanaujla", name = "Karan Aujla")),
            extras = mapOf("artist_slug" to "karanaujla", "url_slug" to "wavy")
        )
        val feed = extension.loadFeed(track)
        assert(feed != null) { "Track feed should not be null" }

        val shelves = feed!!.getPagedData(null).pagedData.loadPage(null).data
        println("Track Info Feed Shelves: ${shelves.size}")
        shelves.forEach { shelf ->
            val count = when (shelf) {
                is Shelf.Lists.Tracks -> shelf.list.size
                is Shelf.Lists.Items -> shelf.list.size
                else -> 0
            }
            println(" - [${shelf.id}] '${shelf.title}' -> $count items")
        }

        assert(shelves.isNotEmpty()) { "Track feed should contain shelves" }
        val moreFromArtist = shelves.firstOrNull { it.id == "track_more_from_artist" }
        assert(moreFromArtist != null) { "Should have 'More from artist' shelf" }

        val playlistsFeaturing = shelves.firstOrNull { it.id == "track_playlists_featuring" }
        assert(playlistsFeaturing != null) { "Should have 'Playlists featuring artist' shelf" }
        val featuringList = (playlistsFeaturing as Shelf.Lists.Items).list
        println("Playlists featuring: ${featuringList.map { it.title }}")
        assert(featuringList.any { it.title.contains("Karan Aujla Essentials", ignoreCase = true) || it.title.contains("808 Pind", ignoreCase = true) }) {
            "Expected 808 Pind or Karan Aujla Essentials in playlists featuring"
        }
    }

    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}
