package fr.forumhfr.redface2

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.ui.redfaceBootstrapWindowBackground
import fr.forumhfr.redface2.feature.settings.AppLauncherIconController
import fr.forumhfr.redface2.navigation.IntentDelivery
import fr.forumhfr.redface2.navigation.RedfaceApp
import fr.forumhfr.redface2.navigation.restartOnLauncherAlias
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeBootstrapStore: ThemeBootstrapStore
    @Inject lateinit var launcherIconController: AppLauncherIconController

    private var latestIntentDelivery by mutableStateOf<IntentDelivery?>(null)
    private var nextIntentDeliveryId = INITIAL_INTENT_DELIVERY_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyBootstrapWindowBackground()
        val restoredDeliveryId = savedInstanceState
            ?.takeIf { it.containsKey(STATE_INTENT_DELIVERY_ID) }
            ?.getLong(STATE_INTENT_DELIVERY_ID)
        val deliveryId = restoredDeliveryId ?: nextIntentDeliveryId
        nextIntentDeliveryId = deliveryId + 1
        latestIntentDelivery = IntentDelivery(intent, deliveryId)

        setContent {
            RedfaceApp(intentDelivery = latestIntentDelivery)
        }
        reconcileLauncherIconOnStartup()
    }

    private fun reconcileLauncherIconOnStartup() {
        lifecycleScope.launch {
            try {
                // The controller reads DataStore and PackageManager on the injected IO dispatcher.
                launcherIconController.reconcile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logger.getLogger("AppLauncherIcon").warning("Launcher reconciliation failed; retry on next startup")
            }
        }
    }

    internal fun restartLauncherIcon(icon: AppLauncherIcon, onFailure: () -> Unit) {
        // Once the effect is consumed, the restart belongs to the Activity rather than the gallery.
        lifecycleScope.launch {
            try {
                restartOnLauncherAlias(this@MainActivity, icon, launcherIconController)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        latestIntentDelivery?.let { outState.putLong(STATE_INTENT_DELIVERY_ID, it.id) }
    }

    /**
     * #386 — the manifest theme follows the OS uiMode, so between the first frame of the window
     * and the first themed composition the user sees the OS background. When the persisted theme
     * forces the opposite of the OS (e.g. dark app under a light system), that's a 1-2 s flash on
     * a cold start. Seed the window background from the synchronous theme mirror ; AppThemeViewModel
     * seeds the composition from the same mirror, so the first drawn frame already matches.
     */
    private fun applyBootstrapWindowBackground() {
        val bootstrap = themeBootstrapStore.read()
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (bootstrap.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }
        val background = redfaceBootstrapWindowBackground(this, dark, bootstrap.colorPreferences)
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        // enableEdgeToEdge() derived the bar ICON contrast from the OS uiMode ; align it with the
        // bootstrap background right away — the #286 SideEffect re-asserts it once composed.
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.isAppearanceLightStatusBars = !dark
        insets.isAppearanceLightNavigationBars = !dark
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntentDelivery = IntentDelivery(intent, nextIntentDeliveryId++)
    }

    private companion object {
        const val INITIAL_INTENT_DELIVERY_ID = 0L
        const val STATE_INTENT_DELIVERY_ID = "intentDeliveryId"
    }
}
