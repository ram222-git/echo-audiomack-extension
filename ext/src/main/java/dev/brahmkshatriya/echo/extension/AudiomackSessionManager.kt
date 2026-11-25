package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Persists the OAuth 1.0a session granted by Audiomack.
 *
 * Only the server-issued token pair + public profile data are stored. No password is ever
 * persisted (Audiomack does not issue refresh tokens; an expired token means re-login).
 * Settings is the storage API provided by the host app.
 */
class AudiomackSessionManager(private val settings: () -> Settings?) {

    data class Session(
        val token: String,
        val tokenSecret: String,
        val expiresAtEpochSeconds: Long?,
        val userId: String,
        val urlSlug: String,
        val name: String,
        val coverUrl: String? = null
    ) {
        fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
            val expiry = expiresAtEpochSeconds ?: return false
            // Treat as expired slightly early to avoid using a token at the boundary.
            return nowSeconds >= expiry - 30
        }

        fun toUser(): User = User(
            id = userId,
            name = name,
            cover = coverUrl?.takeIf { it.isNotBlank() }?.toImageHolder(),
            extras = mapOf(
                "url_slug" to urlSlug,
                "artist_slug" to urlSlug
            )
        )
    }

    private val store: Settings?
        get() = runCatching { settings() }.getOrNull()

    fun load(): Session? {
        val s = store ?: return null
        val session = readStored() ?: return null
        if (session.isExpired()) return null
        val active = s.getString(KEY_ACTIVE)?.takeIf { it.isNotBlank() }
        if (active == null || active != session.userId) return null
        return session
    }

    /** Reads the saved token pair without requiring it to be the active Echo user. */
    private fun readStored(): Session? {
        val s = store ?: return null
        val token = s.getString(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val secret = s.getString(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return null
        val userId = s.getString(KEY_USER_ID)?.takeIf { it.isNotBlank() } ?: return null
        val session = Session(
            token = token,
            tokenSecret = secret,
            expiresAtEpochSeconds = s.getString(KEY_EXPIRY)?.toLongOrNull(),
            userId = userId,
            urlSlug = s.getString(KEY_SLUG).orEmpty(),
            name = s.getString(KEY_NAME)?.takeIf { it.isNotBlank() } ?: userId,
            coverUrl = s.getString(KEY_COVER)?.takeIf { it.isNotBlank() }
        )
        return session
    }

    fun save(session: Session) {
        val s = store ?: return
        s.putString(KEY_TOKEN, session.token)
        s.putString(KEY_SECRET, session.tokenSecret)
        s.putString(KEY_EXPIRY, session.expiresAtEpochSeconds?.toString())
        s.putString(KEY_USER_ID, session.userId)
        s.putString(KEY_SLUG, session.urlSlug)
        s.putString(KEY_NAME, session.name)
        s.putString(KEY_COVER, session.coverUrl)
        s.putString(KEY_ACTIVE, session.userId)
    }

    fun clear() {
        val s = store ?: return
        s.putString(KEY_TOKEN, null)
        s.putString(KEY_SECRET, null)
        s.putString(KEY_EXPIRY, null)
        s.putString(KEY_USER_ID, null)
        s.putString(KEY_SLUG, null)
        s.putString(KEY_NAME, null)
        s.putString(KEY_COVER, null)
        s.putString(KEY_ACTIVE, null)
    }

    /**
     * Marks the persisted session as Echo's active user. `null` means incognito/no selected user,
     * which must NOT delete the credentials: Echo also calls this before restoring a user.
     */
    fun setActiveUser(user: User?) {
        val s = store ?: return
        if (user == null) {
            s.putString(KEY_ACTIVE, null)
            return
        }
        val session = readStored()
        if (session != null && session.userId == user.id) {
            s.putString(KEY_ACTIVE, user.id)
        }
    }

    fun currentUser(): User? = load()?.toUser()

    private companion object {
        const val KEY_TOKEN = "am_oauth_token"
        const val KEY_SECRET = "am_oauth_token_secret"
        const val KEY_EXPIRY = "am_oauth_token_expiration"
        const val KEY_USER_ID = "am_user_id"
        const val KEY_SLUG = "am_user_slug"
        const val KEY_NAME = "am_user_name"
        const val KEY_COVER = "am_user_cover"
        const val KEY_ACTIVE = "am_active_user"
    }
}
