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
import dagger.hilt.android.AndroidEntryPoint
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceDarkColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import fr.forumhfr.redface2.navigation.IntentDelivery
import fr.forumhfr.redface2.navigation.RedfaceApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeBootstrapStore: ThemeBootstrapStore

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
        val background = when {
            dark && bootstrap.amoledEnabled -> RedfaceAmoledColorScheme.background
            dark -> RedfaceDarkColorScheme.background
            else -> RedfaceLightColorScheme.background
        }
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
