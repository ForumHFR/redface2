package fr.forumhfr.redface2.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.network.cookie.CookieStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie

@Singleton
class DataStoreCookieStore @Inject constructor(
    @param:CookieDataStore private val dataStore: DataStore<Preferences>,
) : CookieStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val cookiesKey = stringPreferencesKey(KEY_SESSION_COOKIES)

    override fun observe(): Flow<List<Cookie>> = dataStore.data.map { prefs ->
        val raw = prefs[cookiesKey] ?: return@map emptyList()
        runCatching {
            json.decodeFromString<List<CookieDto>>(raw)
                .mapNotNull { it.toCookie() }
                .filter { it.expiresAt > System.currentTimeMillis() }
        }.getOrElse {
            // Fail closed but keep the DataStore flow alive: the next valid write must be
            // observed normally instead of being swallowed by a terminal catch block.
            emptyList()
        }
    }.catch {
        // DataStore-level failure: fail closed so PersistentCookieJar is never stuck
        // waiting forever for its first non-null cache value.
        emit(emptyList())
    }

    override suspend fun save(cookies: List<Cookie>) {
        val dtos = cookies.map { it.toDto() }
        val payload = json.encodeToString<List<CookieDto>>(dtos)
        dataStore.edit { prefs -> prefs[cookiesKey] = payload }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(cookiesKey) }
    }

    private fun Cookie.toDto(): CookieDto = CookieDto(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresAt = expiresAt,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
    )

    private fun CookieDto.toCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .also { if (hostOnly) it.hostOnlyDomain(domain) else it.domain(domain) }
            .path(path)
            .expiresAt(expiresAt)
            .also { if (secure) it.secure() }
            .also { if (httpOnly) it.httpOnly() }
            .build()
    }.getOrNull()

    /**
     * On-disk shape of one persisted cookie. The `@SerialName` annotations freeze the
     * JSON keys against any code-side rename : a Kotlin property rename without an
     * explicit `@SerialName` would silently log out every existing user (the
     * `runCatching` upstream catches the resulting `MissingFieldException` and emits
     * an empty cookie list, which the UI reads as "session expired"). Defaults make
     * a missing key tolerable for older payloads — preferable to wiping the whole
     * cookie list because one new field landed.
     */
    @Serializable
    private data class CookieDto(
        @SerialName("name") val name: String = "",
        @SerialName("value") val value: String = "",
        @SerialName("domain") val domain: String = "",
        @SerialName("path") val path: String = "/",
        @SerialName("expiresAt") val expiresAt: Long = 0L,
        @SerialName("secure") val secure: Boolean = false,
        @SerialName("httpOnly") val httpOnly: Boolean = false,
        @SerialName("hostOnly") val hostOnly: Boolean = false,
    )

    companion object {
        const val KEY_SESSION_COOKIES = "session_cookies"
    }
}
