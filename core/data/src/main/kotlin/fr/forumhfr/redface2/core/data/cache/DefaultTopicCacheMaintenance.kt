package fr.forumhfr.redface2.core.data.cache

import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Default implementation of [TopicCacheMaintenance] — delegates to
 * [TopicDao.clearTopicCache] inside a single Room transaction and wraps the call in
 * [withContext] on the IO dispatcher so the SettingsViewModel can `viewModelScope.launch`
 * without ending up on Main during the DELETE.
 *
 * No DiagnosticsLog write: the operation is a user-initiated maintenance click in
 * Paramètres alpha, the result is surfaced through `SettingsState`, and there is no
 * privacy-sensitive payload to redact.
 */
@Singleton
class DefaultTopicCacheMaintenance @Inject constructor(
    private val topicDao: TopicDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicCacheMaintenance {

    override suspend fun clearTopicCache() {
        withContext(ioDispatcher) {
            topicDao.clearTopicCache()
        }
    }
}
