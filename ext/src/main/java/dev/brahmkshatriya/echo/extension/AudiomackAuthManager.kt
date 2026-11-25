package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.User

/**
 * Login/logout/session lifecycle. Passwords are never written to Settings.
 * Audiomack issues long-lived OAuth 1.0a tokens and has no refresh endpoint, so an expired
 * token results in a LoginRequired error and a fresh sign-in.
 */
class AudiomackAuthManager(
    private val api: AudiomackApiClient,
    private val sessions: AudiomackSessionManager
) {
    suspend fun login(email: String, password: String): User {
        val session = api.login(email.trim(), password)
        sessions.save(session)
        return session.toUser()
    }

    suspend fun logout() {
        try {
            api.logout()
        } finally {
            sessions.clear()
        }
    }

    fun setActiveUser(user: User?) {
        sessions.setActiveUser(user)
    }

    fun currentUser(): User? = sessions.currentUser()

    fun currentSlug(): String? = sessions.load()?.urlSlug?.takeIf { it.isNotBlank() }

    fun requireSlug(): String = currentSlug() ?: throw dev.brahmkshatriya.echo.common.helpers.ClientException.LoginRequired()
}
