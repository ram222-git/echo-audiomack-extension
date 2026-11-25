package dev.brahmkshatriya.echo.extension

import java.net.URI

/**
 * Resolves the full-length, authorized stream URL through Audiomack's own play endpoint.
 *
 * Evidence: `GET /v1/music/play/{id}?hq=1` returns `{signedUrl, playUuid}`; the signed URL is
 * an `audio/mp4` object on `music.audiomack.com` with `Accept-Ranges: bytes`.
 * Nothing is fabricated or upgraded: if the service refuses a stream, this throws.
 */
class AudiomackStreamResolver(
    private val fetchUrl: suspend (trackId: String, preferHighQuality: Boolean) -> String
) {
    constructor(api: AudiomackApiClient) : this({ id, hq -> api.getStreamUrl(id, hq) })

    data class ResolvedStream(val url: String, val quality: Int)

    suspend fun resolve(trackId: String, preferHighQuality: Boolean): ResolvedStream {
        if (trackId.isBlank()) {
            throw AudiomackException(null, null, "This track has no Audiomack id and cannot be played.")
        }
        val url = fetchUrl(trackId, preferHighQuality)
        validate(url)
        return ResolvedStream(url, quality = if (preferHighQuality) 256 else 128)
    }

    private fun validate(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw AudiomackErrorHandler.invalidResponse(
                "Audiomack returned a non-HTTPS stream URL. Playback was refused."
            )
        }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            ?: throw AudiomackErrorHandler.invalidResponse("Audiomack returned an invalid stream URL.")
        if (host != "audiomack.com" && !host.endsWith(".audiomack.com")) {
            throw AudiomackErrorHandler.invalidResponse(
                "Audiomack returned a stream from an unexpected host ($host). Playback was refused."
            )
        }
        val path = runCatching { URI(url).path?.lowercase().orEmpty() }.getOrDefault("")
        if (path.isNotBlank() &&
            listOf(".m4a", ".mp3", ".aac", ".ogg", ".opus", ".m3u8").none { path.endsWith(it) }
        ) {
            throw AudiomackErrorHandler.invalidResponse(
                "Audiomack returned a stream in an unsupported format."
            )
        }
    }
}
