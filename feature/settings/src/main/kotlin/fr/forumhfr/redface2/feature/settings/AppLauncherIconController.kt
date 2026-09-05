package fr.forumhfr.redface2.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps the preference commit and component update serialized with startup repair. */
@Singleton
class AppLauncherIconController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun apply(icon: AppLauncherIcon) {
        require(icon.selectable)
        mutex.withLock {
            withContext(ioDispatcher + NonCancellable) {
                preferences.setAppLauncherIcon(icon)
                // Only the target is enabled here; the previous alias stays live until the restarted
                // process reconciles it, so the current task is not force-removed mid-restart (#326).
                enableLauncherAlias(context, icon)
            }
        }
    }

    suspend fun reconcile() {
        mutex.withLock {
            withContext(ioDispatcher + NonCancellable) {
                val persisted = preferences.observeAppLauncherIcon().first()
                reconcileLauncherIcon(context, persisted)?.let { preferences.setAppLauncherIcon(it) }
            }
        }
    }

    /** Recovery must restore a launchable component even if storage is temporarily unavailable. */
    suspend fun recoverClassic() {
        mutex.withLock {
            withContext(ioDispatcher + NonCancellable) {
                applyLauncherIcon(context, AppLauncherIcon.CLASSIC)
                try {
                    preferences.setAppLauncherIcon(AppLauncherIcon.CLASSIC)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Logger.getLogger("AppLauncherIcon").warning("Could not persist the Classic launcher recovery")
                }
            }
        }
    }
}
