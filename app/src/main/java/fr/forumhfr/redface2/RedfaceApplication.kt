package fr.forumhfr.redface2

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.time.Clock
import javax.inject.Inject

@HiltAndroidApp
class RedfaceApplication : Application() {
    @Inject
    lateinit var clock: Clock

    override fun onCreate() {
        super.onCreate()
        Log.d("RedfaceApplication", "Hilt graph ready at ${clock.instant()}")
    }
}
