package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live checks against the real Audiomack v1 API, no account required.
 * Opt in with `AUDIOMACK_LIVE_TESTS=1` so offline CI stays deterministic.
 */
class AudiomackLiveTest {

    private val extension = AudiomackExtension()

    @Before
    fun setUp() {
        assumeTrue(
            "Set AUDIOMACK_LIVE_TESTS=1 to run live Audiomack checks",
            System.getenv("AUDIOMACK_LIVE_TESTS") == "1"
        )
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
        }
    }

    @Test
    fun `home discover and charts return real shelves`() = runBlocking {
        val feed = (extension as HomeFeedClient).loadHomeFeed()
        val discover = feed.getPagedData(Tab("discover", "Discover")).pagedData.loadPage(null).data
        val charts = feed.getPagedData(Tab("charts", "Charts")).pagedData.loadPage(null).data
        println("discover shelves=${discover.map { it.id }}")
        println("chart shelves=${charts.map { it.id }}")
        assertTrue(discover.isNotEmpty())
        assertTrue(charts.isNotEmpty())
    }

    @Test
    fun `search exposes songs albums artists and playlists`() = runBlocking {
        val client = extension as QuickSearchClient
        val feed = client.loadSearchFeed("Karan Aujla")
        assertEquals(listOf("all", "songs", "albums", "artists", "playlists"), feed.tabs.map { it.id })

        for (id in listOf("songs", "albums", "artists", "playlists")) {
            val shelves = feed.getPagedData(Tab(id, id)).pagedData.loadPage(null).data
            println("$id results=${shelves.size}")
            assertTrue("$id search returned nothing", shelves.isNotEmpty())
        }
        val quick = client.quickSearch("Karan Aujla")
        println("quick search items=${quick.size}")
        assertTrue(quick.isNotEmpty())
    }

    @Test
    fun `album and playlist tracklists are complete`() = runBlocking {
        val album = (extension as AlbumClient).loadAlbum(
            Album(id = "https://audiomack.com/karanaujla/album/aujla-szn-1", title = "")
        )
        val albumTracks = extension.loadTracks(album)!!.getPagedData(null).pagedData.loadPage(null).data
        println("album='${album.title}' tracks=${albumTracks.size}")
        assertTrue(album.title.isNotBlank())
        assertEquals(albumTracks.size.toLong(), album.trackCount)
        assertTrue(albumTracks.all { it.duration != null && it.duration!! > 0 })

        val playlist = (extension as PlaylistClient).loadPlaylist(
            Playlist(
                id = "https://audiomack.com/audiomack-desi/playlist/verified-punjabi",
                title = "",
                isEditable = false
            )
        )
        val playlistTracks = extension.loadTracks(playlist)
            .getPagedData(null).pagedData.loadPage(null).data
        println("playlist='${playlist.title}' tracks=${playlistTracks.size} declared=${playlist.trackCount}")
        assertTrue(playlistTracks.isNotEmpty())
        assertEquals(playlistTracks.size.toLong(), playlist.trackCount)
    }

    @Test
    fun `artist feed returns uploads and albums`() = runBlocking {
        val artist = (extension as ArtistClient).loadArtist(
            Artist(id = "https://audiomack.com/karanaujla", name = "")
        )
        val shelves = extension.loadFeed(artist).getPagedData(null).pagedData.loadPage(null).data
        println("artist='${artist.name}' shelves=${shelves.map { it.id }}")
        assertTrue(artist.name.contains("Karan Aujla", ignoreCase = true))
        assertTrue(shelves.any { it.id == "artist_top_songs" })
        assertTrue(shelves.any { it.id == "artist_albums" })
    }

    @Test
    fun `stream resolves for multiple album and playlist tracks`() = runBlocking {
        val album = (extension as AlbumClient).loadAlbum(
            Album(id = "https://audiomack.com/karanaujla/album/aujla-szn-1", title = "")
        )
        val albumTracks = extension.loadTracks(album)!!.getPagedData(null).pagedData.loadPage(null).data
        val playlist = (extension as PlaylistClient).loadPlaylist(
            Playlist(
                id = "https://audiomack.com/audiomack-desi/playlist/verified-punjabi",
                title = "",
                isEditable = false
            )
        )
        val playlistTracks = extension.loadTracks(playlist).getPagedData(null).pagedData.loadPage(null).data
        val sample = (albumTracks.take(2) + playlistTracks.take(2))
            .filterNot { it.extras.containsKey("premium_user_only") }
        for (track in sample) {
            val media = extension.loadStreamableMedia(track.streamables.single(), isDownload = false)
                as Streamable.Media.Server
            val url = (media.sources.first() as Streamable.Source.Http).request.url
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", AudiomackOAuth.USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            try {
                println("'${track.title}' HEAD=${connection.responseCode} length=${connection.contentLengthLong}")
                assertEquals(200, connection.responseCode)
                assertTrue(connection.contentLengthLong > 1_000_000)
                assertTrue(connection.contentType.orEmpty().startsWith("audio/"))
            } finally {
                connection.disconnect()
            }
        }
        assertTrue("No sample tracks were available", sample.isNotEmpty())
    }

    @Test
    fun `stale track slugs fall back to the numeric detail endpoint`() = runBlocking {
        val track = (extension as TrackClient).loadTrack(
            Track(
                id = "47317203",
                title = "Wavy",
                extras = mapOf(
                    "artist_slug" to "karanaujla",
                    "url_slug" to "wavy__1732123750"
                )
            ),
            isDownload = false
        )
        println("fallback track='${track.title}' id='${track.id}'")
        assertEquals("47317203", track.id)
        assertTrue(track.duration != null && track.duration!! > 0)
    }

    @Test
    fun `album id track resolves to its actual playable song`() = runBlocking {
        val albumRow = Track(
            id = "107504866",
            title = "Heavy",
            artists = listOf(Artist(id = "jackson-whalan", name = "Jackson Whalan")),
            extras = mapOf("artist_slug" to "jackson-whalan", "url_slug" to "heavy")
        )
        val track = (extension as TrackClient).loadTrack(albumRow, isDownload = false)
        println("resolved album row to id='${track.id}' title='${track.title}'")
        assertEquals("107504903", track.id)
        assertTrue(track.duration != null && track.duration!! > 0)

        val media = extension.loadStreamableMedia(track.streamables.single(), isDownload = false)
            as Streamable.Media.Server
        val url = (media.sources.first() as Streamable.Source.Http).request.url
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.setRequestProperty("User-Agent", AudiomackOAuth.USER_AGENT)
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        try {
            println("album song stream=${connection.responseCode} length=${connection.contentLengthLong}")
            assertEquals(200, connection.responseCode)
            assertTrue(connection.contentLengthLong > 1_000_000)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `both credited artists resolve to their real artist ids`() = runBlocking {
        // This track credits two artists, but its payload only carries the uploader's id.
        val track = (extension as TrackClient).loadTrack(
            Track(id = "47317203", title = "Wavy"),
            isDownload = false
        )
        assertEquals(listOf("Karan Aujla", "Jay Trak"), track.artists.map { it.name })

        val client = extension as ArtistClient
        val loaded = track.artists.map { client.loadArtist(it) }
        println("resolved artists=" + loaded.joinToString { "${it.name}:${it.extras["id"]}" })

        assertEquals("Karan Aujla", loaded[0].name)
        assertEquals("Jay Trak", loaded[1].name)
        for (artist in loaded) {
            assertTrue(
                "${artist.name} was not resolved to a numeric Audiomack artist id",
                artist.extras["id"]?.all { it.isDigit() } == true
            )
        }
    }

    @Test
    fun `invalid login surfaces the server validation message`() = runBlocking {
        val api = AudiomackApiClient(AudiomackSessionManager { MockedSettings() })
        try {
            api.login("echo-test-does-not-exist@invalid.example", "not-a-real-password")
            throw AssertionError("Invalid credentials unexpectedly succeeded")
        } catch (e: AudiomackException) {
            println("login error status=${e.statusCode} message=${e.message}")
            assertEquals(422, e.statusCode)
            assertTrue(e.message.contains("email address was not found", ignoreCase = true))
        }
    }

    @Test
    fun `resolves the authorized full-length stream with range support`() = runBlocking {
        val track = (extension as TrackClient).loadTrack(
            Track(id = "47317203", title = "Wavy"),
            isDownload = false
        )
        println("track='${track.title}' duration=${track.duration}ms artists=${track.artists.map { it.name }}")
        assertTrue(track.duration != null && track.duration!! > 120_000)

        val media = extension.loadStreamableMedia(track.streamables.single(), isDownload = false)
            as Streamable.Media.Server
        val source = media.sources.first() as Streamable.Source.Http
        val url = source.request.url
        println("stream=$url")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.setRequestProperty("User-Agent", AudiomackOAuth.USER_AGENT)
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.connect()
        try {
            val code = connection.responseCode
            val length = connection.contentLengthLong
            val type = connection.contentType.orEmpty()
            val ranges = connection.getHeaderField("Accept-Ranges").orEmpty()
            println("HEAD code=$code length=$length type=$type ranges=$ranges")
            assertEquals(200, code)
            assertTrue("Not a full-length media object: $length bytes", length > 1_000_000)
            assertTrue(type.startsWith("audio/"))
            assertEquals("bytes", ranges)
        } finally {
            connection.disconnect()
        }

        val range = URL(url).openConnection() as HttpURLConnection
        range.setRequestProperty("User-Agent", AudiomackOAuth.USER_AGENT)
        range.setRequestProperty("Range", "bytes=0-1023")
        range.connectTimeout = 15_000
        range.readTimeout = 20_000
        range.connect()
        try {
            val body = range.inputStream.use { it.readBytes() }
            println("range code=${range.responseCode} bytes=${body.size}")
            assertEquals(206, range.responseCode)
            assertEquals(1024, body.size)
        } finally {
            range.disconnect()
        }
        assertNotNull(track.streamables.single().id)
    }
}
