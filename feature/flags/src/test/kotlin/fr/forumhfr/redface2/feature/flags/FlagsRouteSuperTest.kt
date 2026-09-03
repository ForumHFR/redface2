package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagsRouteSuperTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `Super body pull-to-refresh calls refresh action`() {
        var refreshCalls = 0
        mountSuperFlagList(
            content = FlagsContent.Flat(listOf(row(topicId = 1, cat = 23, title = "Android"))),
            onRefresh = { refreshCalls++ },
        )

        composeTestRule.onNodeWithTag(FLAGS_LIST_TEST_TAG).performTouchInput { swipeDown() }

        composeTestRule.waitUntil(timeoutMillis = 1_000) { refreshCalls == 1 }
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `Super grouped state renders category headers`() {
        mountSuperFlagList(
            content = FlagsContent.Grouped(
                listOf(
                    section(23, "Technologies Mobiles", row(title = "Android")),
                    section(10, "Programmation", row(cat = 10, title = "Kotlin")),
                ),
            ),
            categoryBandStyle = CategoryBandStyle.SOFT,
        )

        composeTestRule.onNodeWithText("Technologies Mobiles").assertExists()
        composeTestRule.onNodeWithText("Programmation").assertExists()
    }

    @Test
    fun `Super hide-read keeps fully-read grouped sections visible`() {
        mountSuperFlagList(
            content = FlagsContent.Grouped(
                listOf(
                    section(10, "Programmation", row(cat = 10, title = "Kotlin", hasUnread = false)),
                ),
            ),
            hideReadActive = true,
            // SOFT keeps the label case (MINIMAL uppercases it), so the header text is matchable.
            categoryBandStyle = CategoryBandStyle.SOFT,
        )

        composeTestRule.onNodeWithText("Programmation").assertExists()
        composeTestRule.onNodeWithText("Kotlin").assertExists()
        composeTestRule.onNodeWithText("Aucune catégorie avec un message non lu").assertDoesNotExist()
    }

    @Test
    fun `Super empty grouped hide-read state renders the no-unread placeholder`() {
        mountSuperFlagList(
            content = FlagsContent.Grouped(emptyList()),
            hideReadActive = true,
        )

        composeTestRule.onNodeWithText("Aucune catégorie avec un message non lu").assertExists()
    }

    @Test
    fun `Super empty state renders the Super placeholder`() {
        mountSuperFlagList(content = FlagsContent.Flat(emptyList()))

        composeTestRule.onNodeWithText("Aucun super favori").assertExists()
        composeTestRule.onNodeWithText("Les sujets marqués « Super favori » apparaissent ici.").assertExists()
    }

    private fun mountSuperFlagList(
        content: FlagsContent,
        hideReadActive: Boolean = false,
        categoryBandStyle: CategoryBandStyle = CategoryBandStyle.MINIMAL,
        onRefresh: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                FlagListBody(
                    state = superBodyState(
                        content = content,
                        hideReadActive = hideReadActive,
                        categoryBandStyle = categoryBandStyle,
                    ),
                    actions = actions(onRefresh = onRefresh),
                    listState = rememberLazyListState(),
                    manualRefresh = false,
                    onManualRefresh = {},
                    rowActionsEnabled = ::flagRowActionsEnabled,
                    rowClickEnabled = ::flagRowClickEnabled,
                )
            }
        }
    }

    private fun superBodyState(
        content: FlagsContent,
        hideReadActive: Boolean,
        categoryBandStyle: CategoryBandStyle,
    ): FlagsBodyState = FlagsBodyState(
        selectedTab = FlagTab.Super,
        flagsState = FlagsListUiState.Success(content),
        cyanShowsRead = false,
        isRefreshing = false,
        removeFlagState = RemoveFlagState.Idle,
        showDtTab = false,
        dtListState = DtListUiState.Loading,
        dtShowsRead = false,
        dtIsRefreshing = false,
        categoryBandStyle = categoryBandStyle,
        funnyEmptyState = false,
        hideReadActive = hideReadActive,
    )

    private fun actions(onRefresh: () -> Unit): AuthenticatedActions = AuthenticatedActions(
        onSelectTab = {},
        onOpenFlag = {},
        onRefresh = onRefresh,
        onLoginRequested = {},
        onLongPressFlag = {},
        onOpenCategory = {},
    )

    private fun section(
        catId: Int,
        catName: String,
        vararg rows: FlagRowUiModel,
    ): FlagCategorySection = FlagCategorySection(catId = catId, catName = catName, topics = rows.toList())

    private fun row(
        topicId: Int = 1,
        cat: Int = 23,
        title: String,
        hasUnread: Boolean = true,
    ): FlagRowUiModel = Flag(
        cat = cat,
        subcat = null,
        topicId = topicId,
        title = title,
        totalPages = 1,
        replyCount = 0,
        type = FlagType.FAVORITE,
        hasUnread = hasUnread,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    ).toFlagRowUiModel(MarkerStyle.STRIPE)
}
