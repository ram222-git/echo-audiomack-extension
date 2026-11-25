package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Offline, deterministic tests for mapping, OAuth persistence, errors and stream validation. */
class ExtensionUnitTest {

    private val mapper = AudiomackMetadataMapper { false }

    private val sampleTrack = Json.parseToJsonElement(
        """
        {
          "id": "47317203",
          "title": "Wavy (feat. Ikky)",
          "artist": "Karan Aujla",
          "featuring": "Ikky",
          "uploader": {
            "id": "156046",
            "name": "Karan Aujla",
            "url_slug": "karanaujla",
            "image": "https://i.audiomack.com/karanaujla.jpg"
          },
          "duration": "213",
          "image": "https://i.audiomack.com/wavy.jpg",
          "explicit": "yes",
          "genre": "Punjabi",
          "released": "1700000000",
          "stats": { "plays-raw": "1234567" },
          "album_details": {
            "id": "999",
            "title": "Making Memories",
            "url_slug": "making-memories",
            "artist": "Karan Aujla",
            "image": "https://i.audiomack.com/mm.jpg"
          },
          "premium_user_only": "no",
          "status": "ok"
        }
        """.trimIndent()
    ).jsonObject

    @Test
    fun `maps a complete track into an Echo model`() {
        val track = mapper.parseTrack(sampleTrack)

        assertEquals("47317203", track.id)
        assertEquals("Wavy (feat. Ikky)", track.title)
        assertEquals(213_000L, track.duration)
        assertEquals(1_234_567L, track.plays)
        assertEquals(listOf("Punjabi"), track.genres)
        assertEquals("Making Memories", track.album?.title)
        assertEquals(2, track.artists.size)
        assertEquals("Karan Aujla", track.artists[0].name)
        assertEquals("karanaujla", track.artists[0].extras["url_slug"])
        assertEquals("Ikky", track.artists[1].name)
        assertTrue(track.isExplicit)
        assertEquals(Track.Playable.Yes, track.isPlayable)
        assertEquals("47317203", track.streamables.single().id)
        assertEquals("karanaujla", track.extras["artist_slug"])
        assertEquals("999", track.extras["album_id"])
    }

    @Test
    fun `premium-only anonymous tracks are marked unplayable instead of faked`() {
        val premium = Json.parseToJsonElement(
            """{"id":"1","title":"Premium","premium_user_only":"yes","duration":"200"}"""
        ).jsonObject

        val anonymous = mapper.parseTrack(premium)
        assertTrue(anonymous.isPlayable is Track.Playable.No)

        val signedIn = AudiomackMetadataMapper { true }.parseTrack(premium)
        assertEquals(Track.Playable.Yes, signedIn.isPlayable)
    }

    @Test
    fun `an album object is never exposed as a playable track`() {
        val album = Json.parseToJsonElement(
            """{"id":"107504866","title":"Heavy","type":"album","tracks":[{"song_id":"107504903","title":"Heavy","duration":"208"}]}"""
        ).jsonObject
        val track = mapper.parseTrack(album)
        assertTrue(track.isPlayable is Track.Playable.No)
        assertTrue(track.streamables.isEmpty())
    }

    @Test
    fun `featured artist slugs are not trusted for navigation`() {
        val uploader = sampleTrack["uploader"]!!.jsonObject
        val artists = mapper.parseArtists("Karan Aujla, Jay Trak", uploader)

        // The uploader id comes from the API and can be opened by id.
        assertEquals("156046", artists[0].id)
        assertEquals("true", artists[0].extras["slug_verified"])

        // The featured artist has no id in the track payload. Its slug is only a guess and
        // must not be trusted for navigation until it is resolved by name.
        assertEquals("jay-trak", artists[1].id)
        assertNull(artists[1].extras["slug_verified"])
    }

    @Test
    fun `splits slash, comma and featuring artists without inventing names`() {
        val slash = mapper.parseArtists("raftaar / krsna")
        assertEquals(listOf("raftaar", "krsna"), slash.map { it.name })

        val comma = mapper.parseArtists("Karan Aujla, Ikky")
        assertEquals(listOf("Karan Aujla", "Ikky"), comma.map { it.name })

        val fromTitle = mapper.parseArtists(
            artistString = "Karan Aujla",
            uploaderJson = null,
            title = "Wavy (feat. Ikky)"
        )
        assertEquals(listOf("Karan Aujla", "Ikky"), fromTitle.map { it.name })
    }

    @Test
    fun `parses album and playlist URLs into artist and slug`() {
        val album = AudiomackMetadataMapper.extractArtistAndSlug(
            "https://audiomack.com/karanaujla/album/aujla-szn-1?_rsc=abc"
        )
        assertEquals("karanaujla", album.first)
        assertEquals("aujla-szn-1", album.second)

        val playlist = AudiomackMetadataMapper.extractArtistAndSlug(
            "https://audiomack.com/audiomack-desi/playlist/verified-punjabi"
        )
        assertEquals("audiomack-desi", playlist.first)
        assertEquals("verified-punjabi", playlist.second)

        assertEquals("karanaujla", AudiomackMetadataMapper.extractSlug(
            "https://audiomack.com/karanaujla?_rsc=x"
        ))
    }

    @Test
    fun `session persists tokens but not a password and expires correctly`() {
        val settings = MockedSettings()
        val manager = AudiomackSessionManager { settings }
        val session = AudiomackSessionManager.Session(
            token = "token-value",
            tokenSecret = "secret-value",
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 3600,
            userId = "42",
            urlSlug = "test-user",
            name = "Test User",
            coverUrl = null
        )

        manager.save(session)
        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("token-value", loaded!!.token)
        assertEquals("test-user", loaded.urlSlug)
        assertEquals("42", manager.currentUser()?.id)
        assertNull(settings.getString("password"))

        manager.save(session.copy(expiresAtEpochSeconds = System.currentTimeMillis() / 1000 - 1))
        assertNull(manager.load())

        manager.save(session)
        manager.setActiveUser(null)
        assertNull("Deactivating a user must not delete the token", manager.load())
        manager.setActiveUser(session.toUser())
        assertNotNull("Selecting the stored user should reactivate the session", manager.load())

        manager.clear()
        assertNull(manager.load())
    }

    @Test
    fun `maps 401 and server errors to actionable failures`() {
        val anonymous = AudiomackErrorHandler.fromResponse(
            statusCode = 401,
            body = """{"errorcode":"1004","message":"Request unauthorised"}""",
            retryAfterSeconds = null,
            isAuthenticated = false,
            userId = null
        )
        assertTrue(anonymous is ClientException.LoginRequired)

        val signedIn = AudiomackErrorHandler.fromResponse(
            statusCode = 401,
            body = "{}",
            retryAfterSeconds = null,
            isAuthenticated = true,
            userId = "42"
        )
        assertTrue(signedIn is ClientException.Unauthorized)
        assertEquals("42", (signedIn as ClientException.Unauthorized).userId)

        val rateLimited = AudiomackErrorHandler.fromResponse(
            statusCode = 429,
            body = "",
            retryAfterSeconds = 12,
            isAuthenticated = false,
            userId = null
        ) as AudiomackException
        assertTrue(rateLimited.message.contains("rate limit", ignoreCase = true))
        assertEquals(12L, rateLimited.retryAfterSeconds)

        val server = AudiomackErrorHandler.fromResponse(
            statusCode = 503,
            body = "",
            retryAfterSeconds = null,
            isAuthenticated = false,
            userId = null
        ) as AudiomackException
        assertTrue(server.message.contains("temporarily unavailable", ignoreCase = true))

        val login = AudiomackErrorHandler.fromResponse(
            statusCode = 422,
            body = """{"errors":["Login validation failed: That email address was not found."]}""",
            retryAfterSeconds = null,
            isAuthenticated = false,
            userId = null
        ) as AudiomackException
        assertTrue(
            "Login validation detail was not surfaced: ${login.message}",
            login.message.contains("email address was not found", ignoreCase = true)
        )
    }

    @Test
    fun `stream resolver rejects non-Audiomack or insecure URLs`() = runBlocking {
        val good = AudiomackStreamResolver { _, _ ->
            "https://music.audiomack.com/hq/song.m4a?Signature=x"
        }.resolve("47317203", true)
        assertEquals(256, good.quality)

        val badHost = AudiomackStreamResolver { _, _ ->
            "https://evil.example/song.m4a"
        }
        try {
            badHost.resolve("47317203", true)
            fail("Expected non-Audiomack host to be rejected")
        } catch (e: AudiomackException) {
            assertTrue(e.message.contains("unexpected host"))
        }

        val insecure = AudiomackStreamResolver { _, _ ->
            "http://music.audiomack.com/song.m4a"
        }
        try {
            insecure.resolve("47317203", true)
            fail("Expected HTTP URL to be rejected")
        } catch (e: AudiomackException) {
            assertTrue(e.message.contains("non-HTTPS"))
        }
    }
}
