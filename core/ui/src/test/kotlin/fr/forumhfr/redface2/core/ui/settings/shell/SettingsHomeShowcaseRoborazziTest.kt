package fr.forumhfr.redface2.core.ui.settings.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #494 v2 — showcase Roborazzi (rendu JVM, sans device) du SHELL VERROUILLÉ du menu de configuration
 * (direction figée 2026-06-15, Claude + Codex) : Search app bar (pill 56dp → app bar ~72dp) +
 * racine « catégories d'abord » REGROUPÉE par familles (cercles tonals + sous-titres) + NavigationBar
 * 80dp à 5 items. Icônes = vraies Material Symbols Outlined (`ic_ms_*`, plus de placeholder « tune »).
 * Record-only (`roborazzi.test.record=true` forcé dans `core/ui/build.gradle.kts`) → PNG sous
 * `core/ui/build/outputs/roborazzi/` ; gating CI à venir avec le plugin Roborazzi (AGP 9, #781) :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*SettingsHomeShowcaseRoborazziTest*' --console=plain
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SettingsHomeShowcaseRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun Shell() {
        Column(Modifier.fillMaxSize()) {
            SettingsHomeScreen(
                groups = GROUPS,
                searchPlaceholder = "Rechercher un réglage",
                menuContentDescription = "Menu",
                searchContentDescription = "Rechercher dans les réglages",
                onMenuClick = null,
                onSearchClick = {},
                onCategoryClick = {},
                modifier = Modifier.weight(1f),
            )
            ShellBottomBar()
        }
    }

    @Composable
    private fun ShellBottomBar() {
        NavigationBar {
            BOTTOM_ITEMS.forEach { (label, iconRes, selected) ->
                NavigationBarItem(
                    selected = selected,
                    onClick = {},
                    icon = { Icon(painterResource(iconRes), contentDescription = null) },
                    label = { Text(label) },
                )
            }
        }
    }

    @Test
    fun settingsShellLight() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { Shell() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/settings_v2_shell_light.png",
        )
    }

    @Test
    fun settingsShellDark() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = true, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { Shell() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/settings_v2_shell_dark.png",
        )
    }

    @Test
    fun settingsShellAmoled() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = true, amoledTheme = true, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { Shell() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/settings_v2_shell_amoled.png",
        )
    }

    private companion object {
        private fun cat(id: String, title: String, sub: String, icon: Int) =
            SettingsCategoryUi(id, title, sub, icon)

        val GROUPS = listOf(
            SettingsCategoryGroup(
                "appearance", "Apparence",
                listOf(
                    cat(
                        "display", "Affichage", "Thème, AMOLED, densité, police",
                        R.drawable.ic_ms_display_settings,
                    ),
                    cat(
                        "flags", "Drapeaux", "Regroupement, masquer lus, auto-refresh",
                        R.drawable.ic_ms_flag,
                    ),
                    cat(
                        "topic", "Sujets et lecture", "Barre, sondages, navigation",
                        R.drawable.ic_ms_article,
                    ),
                ),
            ),
            SettingsCategoryGroup(
                "communication", "Communication",
                listOf(
                    cat(
                        "mp", "Messages privés", "Badge de non-lus",
                        R.drawable.ic_ms_mail,
                    ),
                    cat(
                        "editing", "Édition et publication", "Confirmation avant envoi",
                        R.drawable.ic_ms_edit_square,
                    ),
                ),
            ),
            SettingsCategoryGroup(
                "content", "Contenu",
                listOf(
                    cat(
                        "images", "Images et upload", "Hébergeur, Client-ID, insertion",
                        R.drawable.ic_ms_add_photo_alternate,
                    ),
                    cat(
                        "start", "Démarrage", "Écran ouvert au lancement",
                        R.drawable.ic_ms_rocket_launch,
                    ),
                ),
            ),
            SettingsCategoryGroup(
                "system", "Système",
                listOf(
                    cat(
                        "network", "Réseau et cache", "Proxy, vider les caches",
                        R.drawable.ic_ms_cached,
                    ),
                    cat(
                        "account", "Compte et à propos", "Compte HFR, version, diagnostics",
                        R.drawable.ic_ms_account_circle,
                    ),
                ),
            ),
            SettingsCategoryGroup(
                "other", "Autres",
                listOf(
                    cat(
                        "upcoming", "À venir", "Fonctionnalités planifiées",
                        R.drawable.ic_ms_upcoming,
                    ),
                ),
            ),
        )
        val BOTTOM_ITEMS = listOf(
            Triple("Drapeaux", R.drawable.ic_ms_flag, false),
            Triple("Forum", R.drawable.ic_ms_forum, false),
            Triple("Recherche", R.drawable.ic_ms_search, false),
            Triple("Messages", R.drawable.ic_ms_mail, false),
            Triple("Réglages", R.drawable.ic_ms_settings, true),
        )
    }
}
