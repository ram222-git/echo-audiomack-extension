package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditPrivacyClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Authenticated integration checks using a locally supplied, already-issued OAuth token.
 * Tokens are read from the environment (loaded from the git-ignored `.env`) and are never
 * written into test sources. Mutations are reverted in `finally` blocks.
 */
class AudiomackAccountLiveTest {

    private val extension = AudiomackExtension()
    private val token = System.getenv("AUDIOMACK_OAUTH_TOKEN").orEmpty()
    private val tokenSecret = System.getenv("AUDIOMACK_OAUTH_TOKEN_SECRET").orEmpty()
    private val userId = System.getenv("AUDIOMACK_USER_ID").orEmpty()
    private val userSlug = System.getenv("AUDIOMACK_USER_SLUG").orEmpty()
    private val userName = System.getenv("AUDIOMACK_USER_NAME").orEmpty()

    @Before
    fun setUp() {
        assumeTrue(
            "Set AUDIOMACK_ACCOUNT_TESTS=1 and provide the local OAuth token to run account checks",
            System.getenv("AUDIOMACK_ACCOUNT_TESTS") == "1"
        )
        assumeTrue("Missing local OAuth token", token.isNotBlank() && tokenSecret.isNotBlank())
        assumeTrue("Missing local user id/slug", userId.isNotBlank() && userSlug.isNotBlank())

        val settings = MockedSettings()
        AudiomackSessionManager { settings }.save(
            AudiomackSessionManager.Session(
                token = token,
                tokenSecret = tokenSecret,
                expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 365L * 24 * 3600,
                userId = userId,
                urlSlug = userSlug,
                name = userName.ifBlank { userSlug }
            )
        )
        extension.setSettings(settings)
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
        }
    }

    @Test
    fun `session is restored and library tabs load`() = runBlocking {
        val user = extension.getCurrentUser()
        assertNotNull("Persisted session was not restored", user)
        println("session user=${user!!.id}/${user.extras["url_slug"]}")

        val feed = (extension as LibraryFeedClient).loadLibraryFeed()
        println("library tabs=${feed.tabs.map { it.id }}")
        assertEquals(
            listOf("recent", "liked", "albums", "playlists", "following"),
            feed.tabs.map { it.id }
        )
        val recent = feed.getPagedData(Tab("recent", "Recently played"))
            .pagedData.loadPage(null).data
        println("recent shelves=${recent.map { it.id }}")
        assertTrue(recent.isNotEmpty())
    }

    @Test
    fun `authenticated related tracks are parsed from a bare array`() = runBlocking {
        val track = (extension as TrackClient).loadTrack(
            Track(id = "47317203", title = "Wavy"),
            isDownload = false
        )
        val shelves = extension.loadFeed(track).getPagedData(null).pagedData.loadPage(null).data
        println("track feed shelves=${shelves.map { it.id }}")
        val related = shelves.filterIsInstance<Shelf.Lists.Tracks>()
            .firstOrNull { it.id == "track_related" }
        assertNotNull("Related tracks shelf missing for an authenticated session", related)
        println("related tracks=${related!!.list.size}")
        assertTrue(related.list.isNotEmpty())
    }

    @Test
    fun `like a track and restore the previous state`() = runBlocking {
        val track = Track(id = "47317203", title = "Wavy")
        val wasLiked = extension.isItemLiked(track)
        println("initial track liked=$wasLiked")
        try {
            extension.likeItem(track, !wasLiked)
            assertEquals("Track like did not persist", !wasLiked, extension.isItemLiked(track))
        } finally {
            extension.likeItem(track, wasLiked)
        }
        assertEquals("Track like was not restored", wasLiked, extension.isItemLiked(track))
    }

    @Test
    fun `save an album and restore the previous state`() = runBlocking {
        val album = (extension as AlbumClient).loadAlbum(
            Album(id = "https://audiomack.com/karanaujla/album/aujla-szn-1", title = "")
        )
        val wasSaved = (extension as SaveClient).isItemSaved(album)
        println("album '${album.title}' initially saved=$wasSaved")
        try {
            extension.saveToLibrary(album, !wasSaved)
            assertEquals("Album save did not persist", !wasSaved, extension.isItemSaved(album))
        } finally {
            extension.saveToLibrary(album, wasSaved)
        }
        assertEquals("Album save was not restored", wasSaved, extension.isItemSaved(album))
    }

    @Test
    fun `follow and unfollow an artist`() = runBlocking {
        val artist = (extension as ArtistClient).loadArtist(
            Artist(id = "https://audiomack.com/karanaujla", name = "Karan Aujla")
        )
        val client = extension as FollowClient
        val wasFollowing = client.isFollowing(artist)
        println("artist '${artist.name}' initially following=$wasFollowing")
        assertNotNull(client.getFollowersCount(artist))
        try {
            client.followItem(artist, !wasFollowing)
            assertEquals("Follow did not persist", !wasFollowing, client.isFollowing(artist))
        } finally {
            client.followItem(artist, wasFollowing)
        }
        assertEquals("Follow was not restored", wasFollowing, client.isFollowing(artist))
    }

    @Test
    fun `create add edit privatize remove and delete a playlist`() = runBlocking {
        var created: Playlist? = null
        var deleted = false
        try {
            created = (extension as PlaylistEditClient).createPlaylist(
                "Echo integration ${System.currentTimeMillis()}",
                "Temporary test playlist"
            )
            println("created playlist id=${created.id} genre=${created.extras["genre"]}")
            assertTrue(created.id.isNotBlank())

            val client = extension as PlaylistEditClient
            val track = (extension as TrackClient).loadTrack(
                Track(id = "47317203", title = "Wavy"),
                isDownload = false
            )
            client.addTracksToPlaylist(created, emptyList(), 0, listOf(track))
            val withTrack = (extension as PlaylistClient).loadPlaylist(created)
            val tracks = extension.loadTracks(withTrack).getPagedData(null).pagedData.loadPage(null).data
            println("playlist tracks after add=${tracks.size}")
            assertEquals(1, tracks.size)

            client.editPlaylistMetadata(withTrack, "Echo edited", "Updated description")
            val edited = (extension as PlaylistClient).loadPlaylist(withTrack)
            assertEquals("Echo edited", edited.title)
            assertEquals("Updated description", edited.description)

            (extension as PlaylistEditPrivacyClient).setPrivacy(edited, true)
            val private = (extension as PlaylistClient).loadPlaylist(edited)
            assertTrue("Playlist was not made private", private.isPrivate)

            client.removeTracksFromPlaylist(private, tracks, listOf(0))
            val empty = (extension as PlaylistClient).loadPlaylist(private)
            val afterRemove = extension.loadTracks(empty)
                .getPagedData(null).pagedData.loadPage(null).data
            println("playlist tracks after remove=${afterRemove.size}")
            assertTrue(afterRemove.isEmpty())

            client.deletePlaylist(empty)
            deleted = true
            try {
                (extension as PlaylistClient).loadPlaylist(empty)
                fail("Deleted playlist was still readable")
            } catch (e: AudiomackException) {
                println("deleted playlist lookup status=${e.statusCode}")
                assertEquals(404, e.statusCode)
            }
        } finally {
            val playlist = created
            if (playlist != null && !deleted) {
                runCatching { (extension as PlaylistEditClient).deletePlaylist(playlist) }
            }
        }
        assertTrue(deleted)
    }
}
