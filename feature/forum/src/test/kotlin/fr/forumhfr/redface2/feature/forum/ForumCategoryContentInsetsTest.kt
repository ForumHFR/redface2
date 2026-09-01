package fr.forumhfr.redface2.feature.forum

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1149 — MOUNTED proof that [ForumCategoryContent] applies the system-bar insets exactly ONCE.
 *
 * Robolectric reports no system bars, so the test dispatches synthetic window insets
 * ([WindowInsetsCompat]) to the Compose host view (the listener Compose installs through
 * `WindowInsetsHolder` turns them into `WindowInsets.systemBars`, which the `Scaffold` default
 * `contentWindowInsets` reads). Geometry is asserted as a DELTA against a zero-inset baseline
 * taken through the same dispatch path, so the proof does not depend on the host activity's
 * decor: with a 24 dp status bar the inset-padded column (and its first element, the category
 * title) must move down by exactly 24 dp — the pre-fix `.padding(padding).statusBarsPadding()`
 * chain moved it by 48 dp — and lose exactly one navigation-bar height at the bottom (or side).
 *
 * Two record-only Roborazzi captures (light / dark, red bands overlaying the simulated bars)
 * complement the assertions for human review:
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:forum:testDebugUnitTest \
 *         --tests '*ForumCategoryContentInsetsTest*' --console=plain --no-daemon
 *
 * Output: `feature/forum/build/outputs/roborazzi/forum_category_system_bars_*.png` (gitignored).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ForumCategoryContentInsetsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var hostView: View
    private lateinit var density: Density

    @Test
    fun `phone portrait - status bar top and navigation bar bottom are applied once`() {
        mount()
        applySystemBars(statusTop = 0.dp)
        val content = contentBounds()
        val title = titleBounds()

        applySystemBars(statusTop = STATUS_BAR, navBottom = NAV_BAR)

        // Pre-fix: top at 2 × STATUS_BAR, height shortened by 2 × (STATUS_BAR + NAV_BAR).
        compose.onNodeWithTag(FORUM_CATEGORY_CONTENT_TAG)
            .assertTopPositionInRootIsEqualTo(content.top + STATUS_BAR)
            .assertLeftPositionInRootIsEqualTo(content.left)
            .assertWidthIsEqualTo(content.right - content.left)
            .assertHeightIsEqualTo((content.bottom - content.top) - STATUS_BAR - NAV_BAR)
        // First visible element of the screen: shifted by the status bar once, never twice.
        compose.onNodeWithText(CATEGORY_NAME)
            .assertTopPositionInRootIsEqualTo(title.top + STATUS_BAR)
    }

    @Test
    fun `landscape 3-button - side navigation bar is applied once and the bottom is untouched`() {
        mount()
        applySystemBars(statusTop = 0.dp)
        val content = contentBounds()

        applySystemBars(statusTop = STATUS_BAR, navRight = NAV_BAR)

        compose.onNodeWithTag(FORUM_CATEGORY_CONTENT_TAG)
            .assertTopPositionInRootIsEqualTo(content.top + STATUS_BAR)
            .assertHeightIsEqualTo((content.bottom - content.top) - STATUS_BAR)
            .assertLeftPositionInRootIsEqualTo(content.left)
            .assertWidthIsEqualTo((content.right - content.left) - NAV_BAR)
    }

    @Test
    fun recordLight() = record(darkTheme = false, suffix = "light")

    @Test
    fun recordDark() = record(darkTheme = true, suffix = "dark")

    private fun record(darkTheme: Boolean, suffix: String) {
        mount(darkTheme = darkTheme, simulateBars = true)
        applySystemBars(statusTop = STATUS_BAR, navBottom = NAV_BAR)
        compose.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/forum_category_system_bars_$suffix.png",
        )
    }

    private fun mount(darkTheme: Boolean = false, simulateBars: Boolean = false) {
        compose.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current
            SideEffect {
                hostView = view
                density = currentDensity
            }
            RedfaceTheme(darkTheme = darkTheme, amoledTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ForumCategoryContent(
                        state = contentState(),
                        highlightTitle = null,
                        onOpenTopic = {},
                        onCreateTopic = { _, _ -> },
                        callbacks = ForumCategoryCallbacks(),
                    )
                    if (simulateBars) {
                        // Where the real bars would be drawn — content must start right below /
                        // end right above them, with no second band-sized gap.
                        SystemBarOverlay(height = STATUS_BAR, alignment = Alignment.TopCenter)
                        SystemBarOverlay(height = NAV_BAR, alignment = Alignment.BottomCenter)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Dispatches synthetic system-bar insets to the Compose host view through
     * `View.dispatchApplyWindowInsets` — the same entry point the window uses on a device. The
     * compat builder keeps the test free of API 30-only platform calls (lint `NewApi`).
     */
    private fun applySystemBars(statusTop: Dp, navBottom: Dp = 0.dp, navRight: Dp = 0.dp) {
        val insets = with(density) {
            WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, statusTop.roundToPx(), 0, 0),
                )
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, navRight.roundToPx(), navBottom.roundToPx()),
                )
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
        }
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
        compose.waitForIdle()
    }

    private fun contentBounds(): DpRect =
        compose.onNodeWithTag(FORUM_CATEGORY_CONTENT_TAG).getUnclippedBoundsInRoot()

    private fun titleBounds(): DpRect =
        compose.onNodeWithText(CATEGORY_NAME).getUnclippedBoundsInRoot()

    private fun contentState(): CategoryUiState {
        val topics = listOf(
            topic(topicId = 1, title = "Sujet épinglé de démonstration", isSticky = true),
            topic(topicId = 2, title = "Premier sujet ordinaire"),
            topic(topicId = 3, title = "Deuxième sujet ordinaire", isLocked = true),
        )
        return CategoryUiState(
            cat = CAT,
            categoryName = CATEGORY_NAME,
            initialSubcat = null,
            selectedSubcat = null,
            page = 1,
            pageCount = 3,
            subcategories = SubcategoriesUiState.Content(
                listOf(
                    SubCategory(id = 550, name = "Android", parentCategoryId = CAT),
                    SubCategory(id = 551, name = "iOS", parentCategoryId = CAT),
                ),
            ),
            topics = TopicsUiState.Content(
                TopicListPage(
                    cat = CAT,
                    subcat = null,
                    page = 1,
                    resultsPerPage = 50,
                    totalTopics = 120,
                    topics = topics,
                ),
            ),
            searchQuery = "",
            filteredTopics = topics,
            isRefreshing = false,
            // Authenticated shape: FAB rendered (Scaffold slot) and #1131 clearance reserved.
            canCreateTopic = true,
        )
    }

    private fun topic(
        topicId: Int,
        title: String,
        isSticky: Boolean = false,
        isLocked: Boolean = false,
    ): TopicSummary = TopicSummary(
        cat = CAT,
        subcat = null,
        topicId = topicId,
        title = title,
        author = "auteur",
        lastReplyAuthor = "dernier auteur",
        lastReplyAt = "2026-08-30 12:34",
        replyCount = 12,
        totalPages = 1,
        isSticky = isSticky,
        isLocked = isLocked,
        hasUnread = null,
        lastReadPage = null,
        lastPostReadId = null,
        flagType = null,
    )

    private companion object {
        const val CAT = 23
        const val CATEGORY_NAME = "Technologies Mobiles"
        val STATUS_BAR = 24.dp
        val NAV_BAR = 48.dp
    }
}

@Composable
private fun BoxScope.SystemBarOverlay(height: Dp, alignment: Alignment) {
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxWidth()
            .height(height)
            .background(Color.Red.copy(alpha = 0.35f)),
    )
}
