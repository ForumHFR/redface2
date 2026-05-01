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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie

@Singleton
class DataStoreCookieStore @Inject constructor(
    @param:CookieDataStore private val dataStore: DataStore<Preferences>,
) : CookieStore {

    private val json = Json { ignoreUnknownKeys = true }
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

    @Serializable
    private data class CookieDto(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    companion object {
        const val KEY_SESSION_COOKIES = "session_cookies"
    }
}
