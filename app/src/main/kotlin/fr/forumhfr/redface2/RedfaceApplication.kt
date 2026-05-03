package fr.forumhfr.redface2

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import fr.forumhfr.redface2.core.data.cache.CacheInvalidator
import javax.inject.Inject

@HiltAndroidApp
class RedfaceApplication : Application() {

    @Inject lateinit var cacheInvalidator: CacheInvalidator

    override fun onCreate() {
        super.onCreate()
        // Wires the auth-state listener that wipes per-user caches on logout /
        // account switch (cf. [CacheInvalidator]). Started here so the listener
        // is alive for the full process lifetime — there is no point trying to
        // stop it before the process dies.
        cacheInvalidator.start()
    }
}
