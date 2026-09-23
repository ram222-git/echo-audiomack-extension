package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
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
        println("Loaded Track: ${loadedTrack.title} by ${loadedTrack.artists.firstOrNull()?.name}")
        println("Duration: ${loadedTrack.duration} ms (${(loadedTrack.duration ?: 0) / 1000} seconds)")
        assert(loadedTrack.duration != null && loadedTrack.duration!! > 30000L) {
            "Track duration is null or shorter than 30s: ${loadedTrack.duration}"
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
