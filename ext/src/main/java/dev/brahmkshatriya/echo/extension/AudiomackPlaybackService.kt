package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia

/**
 * Turns an Echo [Streamable] into a playable progressive HTTP source.
 * Echo's Media3 player owns background playback, queueing, seeking, audio focus and
 * lock-screen controls; this service only supplies the authorized media source.
 */
class AudiomackPlaybackService(private val resolver: AudiomackStreamResolver) {

    suspend fun loadMedia(streamable: Streamable, preferHighQuality: Boolean): Streamable.Media {
        val embedded = streamable.extras["streaming_url"]?.takeIf { it.isNotBlank() }
        val resolved = if (embedded != null) {
            val quality = streamable.quality.takeIf { it > 0 } ?: 128
            AudiomackStreamResolver.ResolvedStream(embedded, quality)
        } else {
            resolver.resolve(streamable.id, preferHighQuality)
        }

        return resolved.url.toServerMedia(
            headers = mapOf(
                "User-Agent" to AudiomackOAuth.USER_AGENT,
                "Referer" to "https://audiomack.com/",
                "Origin" to "https://audiomack.com"
            )
        )
    }
}
