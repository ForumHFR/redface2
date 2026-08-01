package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #895 (quick win 3) — the top-bar pill describes the DISPLAYED content, provisional cache
 * included : « page X / Y » with the discreet refresh hairline + the « actualisation en cours »
 * a11y description while the refresh is in flight, the same pagination without them once
 * settled, and « Chargement… » ONLY in pure Loading (no content on screen — the #877 guarantee).
 * The pill string goes through the REAL [topicBarPageIndicator] derivation, not a hand-fed
 * stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class TopicTopBarProvisionalPillTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a provisional page shows its own pagination, the hairline and the refreshing description`() {
        setBar(sampleState(mode = loadedMode(provisional = true)))

        composeTestRule.onNodeWithText("page 3 / 10").assertExists()
        composeTestRule.onNodeWithTag(TOPIC_REFRESH_HAIRLINE_TAG, useUnmergedTree = true).assertExists()
        composeTestRule
            .onNodeWithContentDescription("Page 3 sur 10, actualisation en cours")
            .assertExists()
    }

    @Test
    fun `a settled page keeps the pagination and drops the hairline and the description`() {
        setBar(sampleState(mode = loadedMode(provisional = false)))

        composeTestRule.onNodeWithText("page 3 / 10").assertExists()
        composeTestRule
            .onNodeWithTag(TOPIC_REFRESH_HAIRLINE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("Page 3 sur 10, actualisation en cours")
            .assertDoesNotExist()
    }

    @Test
    fun `pure Loading keeps the Chargement pill and no hairline`() {
        setBar(sampleState(mode = TopicUiState.Mode.Loading))

        composeTestRule.onNodeWithText("Chargement…").assertExists()
        composeTestRule
            .onNodeWithTag(TOPIC_REFRESH_HAIRLINE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private fun setBar(state: TopicUiState) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val loaded = state.mode as? TopicUiState.Mode.Loaded
                TopicTopBar(
                    state = state,
                    barTitle = "Un sujet",
                    barPageIndicator = topicBarPageIndicator(state, loaded),
                    backLabel = "Retour",
                    scrollBehavior = null,
                    onBack = {},
                    onIntent = {},
                    loaded = loaded,
                )
            }
        }
    }

    private fun loadedMode(provisional: Boolean): TopicUiState.Mode.Loaded =
        TopicUiState.Mode.Loaded(
            topic = Topic(
                cat = 23,
                post = 35421,
                subcat = 550,
                title = "Un sujet",
                posts = emptyList(),
                page = 3,
                totalPages = 10,
                isFirstPostOwner = false,
                poll = null,
            ),
            provisional = provisional,
        )

    private fun sampleState(mode: TopicUiState.Mode): TopicUiState = TopicUiState(
        request = TopicRequest(cat = 23, post = 35421, page = 3, scrollTo = null),
        mode = mode,
        availablePages = emptyList(),
    )
}
