package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings

/** In-memory Settings implementation used by unit tests. */
class MockedSettings : Settings {
    private val values = mutableMapOf<String, Any?>()

    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

    override fun getInt(key: String): Int? = values[key] as? Int

    override fun getString(key: String): String? = values[key] as? String

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String): Set<String>? = values[key] as? Set<String>

    override fun putBoolean(key: String, value: Boolean?) {
        values[key] = value
    }

    override fun putInt(key: String, value: Int?) {
        values[key] = value
    }

    override fun putString(key: String, value: String?) {
        values[key] = value
    }

    override fun putStringSet(key: String, value: Set<String>?) {
        values[key] = value
    }
}
