package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #603 PR3 — showcase Roborazzi (rendu JVM, record-only) de la LIGNE de drapeau refondue (ADR-017) :
 * marqueur configurable (barre de couleur par défaut / pastille tonale+icône catégorie / dot),
 * désaturation des lus, décoration favori, et pastille « pages à lire » en fin de ligne. PNG sous
 * `core/ui/build/outputs/roborazzi/` pour revue visuelle avant/après :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*FlagItemRefonteRoborazziTest*' --console=plain
 *
 * #814 — la pastille est teintée selon le RETARD (1-2 / 3-9 / ≥ 10 pages), plus selon le drapeau. Le
 * showcase couvre les trois paliers sur deux couleurs de drapeau chacun, aux bornes exactes des
 * paliers (1, 3, 10) plus les valeurs historiques (2, 8, 26), en light / dark / AMOLED : un cyan et un
 * rouge au même retard doivent porter la même pastille.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class FlagItemRefonteRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun Showcase() {
        Column(Modifier.fillMaxSize()) {
            // Default STRIPE marker across the row states.
            FlagRow(SAMPLE_CYAN_UNREAD, MarkerStyle.STRIPE)
            FlagItemDivider()
            FlagRow(SAMPLE_CYAN_READ, MarkerStyle.STRIPE)
            FlagItemDivider()
            FlagRow(SAMPLE_FAVORITE, MarkerStyle.STRIPE)
            FlagItemDivider()
            FlagRow(SAMPLE_RED_MANYPAGES, MarkerStyle.STRIPE)
            FlagItemDivider()
            // #814 — lag tiers at their exact lower bounds, on the OTHER flag colour than the rows
            // above (LOW on red, MEDIUM / HIGH on cyan) : the pill must not follow the flag colour.
            FlagRow(SAMPLE_LAG_LOW_RED, MarkerStyle.STRIPE)
            FlagItemDivider()
            FlagRow(SAMPLE_LAG_MEDIUM_CYAN, MarkerStyle.STRIPE)
            FlagItemDivider()
            FlagRow(SAMPLE_LAG_HIGH_CYAN, MarkerStyle.STRIPE)
            FlagItemDivider()
            // The three marker styles on the same unread cyan topic.
            Row(
                // #603 — STRIPE marker is now fillMaxHeight; bound it with IntrinsicSize.Min so the
                // standalone showcase row sizes to the tallest marker (PASTILLE), not the screen.
                modifier = Modifier
                    .padding(16.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                MarkerStyle.entries.forEach { style ->
                    FlagMarker(
                        style = style,
                        type = FlagType.CYAN,
                        isFavorite = false,
                        hasUnread = true,
                        categoryIconRes = fr.forumhfr.redface2.core.ui.icon.categoryIcon(1),
                    )
                }
            }
        }
    }

    @Composable
    private fun FlagRow(flag: Flag, style: MarkerStyle) {
        FlagItem(
            flag = flag,
            metadata = FlagMetadata(start = flag.lastReplyAuthor, end = "01-05-2026 à 17:07"),
            onClick = {},
            markerStyle = style,
        )
    }

    @Test
    fun flagListRefonteLight() {
        capture(darkTheme = false, amoled = false, name = "flags_pr3_row_light")
    }

    @Test
    fun flagListRefonteDark() {
        capture(darkTheme = true, amoled = false, name = "flags_pr3_row_dark")
    }

    @Test
    fun flagListRefonteAmoled() {
        capture(darkTheme = true, amoled = true, name = "flags_pr3_row_amoled")
    }

    private fun capture(darkTheme: Boolean, amoled: Boolean, name: String) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = darkTheme, amoledTheme = amoled, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { Showcase() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }

    private companion object {
        private val BASE = Flag(
            cat = 1,
            subcat = null,
            topicId = 0,
            title = "",
            totalPages = 1,
            replyCount = 0,
            type = FlagType.CYAN,
            isFavorite = false,
            hasUnread = true,
            lastReadPage = 1,
            lastPostReadId = null,
            firstPostAuthor = "OP",
            lastReplyAuthor = "",
            lastReplyAt = "2026-05-01 17:07",
        )

        val SAMPLE_CYAN_UNREAD = BASE.copy(
            topicId = 1, cat = 1, totalPages = 431, lastReadPage = 429,
            title = "[Topic] Hardware — config du moment et retours", lastReplyAuthor = "Kermine",
        )
        val SAMPLE_CYAN_READ = BASE.copy(
            topicId = 2, cat = 10, hasUnread = false, totalPages = 12, lastReadPage = 12,
            title = "Kotlin / Compose — questions diverses", lastReplyAuthor = "Shaad",
        )
        val SAMPLE_FAVORITE = BASE.copy(
            topicId = 3, cat = 5, isFavorite = true, totalPages = 88, lastReadPage = 80,
            title = "Le topic des jeux indé à suivre", lastReplyAuthor = "tinc",
        )
        val SAMPLE_RED_MANYPAGES = BASE.copy(
            topicId = 4, cat = 13, type = FlagType.RED, totalPages = 1726, lastReadPage = 1700,
            title = "Le topic de la blague Carambar et autres réjouissances", lastReplyAuthor = "Lt Ripley",
        )

        // #814 — one row per lag tier at its lower bound (pagesToRead = 1 / 3 / 10).
        val SAMPLE_LAG_LOW_RED = BASE.copy(
            topicId = 5, cat = 13, type = FlagType.RED, totalPages = 52, lastReadPage = 51,
            title = "[#814] Rouge, 1 page de retard — pastille neutre", lastReplyAuthor = "thibw",
        )
        val SAMPLE_LAG_MEDIUM_CYAN = BASE.copy(
            topicId = 6, cat = 1, type = FlagType.CYAN, totalPages = 120, lastReadPage = 117,
            title = "[#814] Cyan, 3 pages de retard — pastille accentuée", lastReplyAuthor = "thibw",
        )
        val SAMPLE_LAG_HIGH_CYAN = BASE.copy(
            topicId = 7, cat = 1, type = FlagType.CYAN, totalPages = 310, lastReadPage = 300,
            title = "[#814] Cyan, 10 pages de retard — pastille alerte", lastReplyAuthor = "thibw",
        )
    }
}
