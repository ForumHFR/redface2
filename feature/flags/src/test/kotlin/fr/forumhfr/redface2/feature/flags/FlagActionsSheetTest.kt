package fr.forumhfr.redface2.feature.flags

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class FlagActionsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `browser action opens the topic URL in the resolved default browser`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        Shadows.shadowOf(application.packageManager).addResolveInfoForIntent(
            probe,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = BROWSER_PACKAGE
                    name = "$BROWSER_PACKAGE.BrowserActivity"
                }
            },
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                FlagActionsSheet(
                    flag = flag(),
                    categoryName = "Discussions",
                    isSuperFavorite = false,
                    actions = FlagSheetActions(
                        onOpen = {},
                        onReply = {},
                        onToggleSuperFavorite = {},
                        onRemove = {},
                        onDismiss = {},
                    ),
                )
            }
        }

        compose.onNodeWithText("Ouvrir dans le navigateur").performClick()

        val started = Shadows.shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(BROWSER_PACKAGE, started.`package`)
        assertEquals(TOPIC_URL, started.data.toString())
    }

    @Test
    fun `orphan super favorite exposes local removal instead of flag removal`() {
        var toggleSuperFavoriteCalls = 0
        var removeFlagCalls = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                FlagActionsSheet(
                    flag = flag().copy(cat = 0, resolvedFromServer = false),
                    categoryName = "Catégorie 0",
                    isSuperFavorite = true,
                    actions = FlagSheetActions(
                        onOpen = {},
                        onReply = {},
                        onToggleSuperFavorite = { toggleSuperFavoriteCalls += 1 },
                        onRemove = { removeFlagCalls += 1 },
                        onDismiss = {},
                    ),
                )
            }
        }

        compose.onNodeWithText("Retirer des super favoris").performClick()
        compose.onNodeWithText("Retirer le drapeau").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(1, toggleSuperFavoriteCalls)
            assertEquals(0, removeFlagCalls)
        }
    }

    @Test
    fun `a fallback super favorite with a known cat never offers the server removal`() {
        var toggleSuperFavoriteCalls = 0
        var removeFlagCalls = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                FlagActionsSheet(
                    flag = flag().copy(resolvedFromServer = false),
                    categoryName = "Discussions",
                    isSuperFavorite = true,
                    actions = FlagSheetActions(
                        onOpen = {},
                        onReply = {},
                        onToggleSuperFavorite = { toggleSuperFavoriteCalls += 1 },
                        onRemove = { removeFlagCalls += 1 },
                        onDismiss = {},
                    ),
                )
            }
        }

        compose.onNodeWithText("Retirer des super favoris").performClick()
        compose.onNodeWithText("Retirer le drapeau").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(1, toggleSuperFavoriteCalls)
            assertEquals(0, removeFlagCalls)
        }
    }

    @Test
    fun `metadata omits extra pages when the tap opens page 60 of 60`() {
        showFlag(flag().copy(totalPages = 60, lastReadPage = 59, lastPosition = 2360))

        compose.onNodeWithText("page 59 / 60").assertExists()
        compose.onNodeWithText("+1 à lire", substring = true).assertDoesNotExist()
    }

    @Test
    fun `metadata shows one extra page when the tap stays on page 59 of 60`() {
        showFlag(flag().copy(totalPages = 60, lastReadPage = 59, lastPosition = 2340))

        compose.onNodeWithText("page 59 / 60 · +1 à lire").assertExists()
    }

    @Test
    fun `metadata omits extra pages for a read flag with a positive page counter`() {
        showFlag(flag().copy(totalPages = 60, lastReadPage = 59, lastPosition = 2360, hasUnread = false))

        compose.onNodeWithText("page 59 / 60").assertExists()
        compose.onNodeWithText("+1 à lire", substring = true).assertDoesNotExist()
    }

    private fun showFlag(flag: Flag) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                FlagActionsSheet(
                    flag = flag,
                    categoryName = "Discussions",
                    isSuperFavorite = false,
                    actions = FlagSheetActions(
                        onOpen = {},
                        onReply = {},
                        onToggleSuperFavorite = {},
                        onRemove = {},
                        onDismiss = {},
                    ),
                )
            }
        }
    }

    private fun flag(): Flag = Flag(
        cat = 13,
        subcat = null,
        topicId = 35395,
        title = "Topic Redface 2",
        totalPages = 412,
        replyCount = 16_000,
        type = FlagType.CYAN,
        hasUnread = true,
        lastReadPage = 411,
        lastPostReadId = 12_345L,
        firstPostAuthor = "XaTriX",
        lastReplyAuthor = "Alice",
        lastReplyAt = "2026-08-30 12:00",
    )

    private companion object {
        const val BROWSER_PACKAGE = "com.example.browser"
        const val TOPIC_URL =
            "https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=13&post=35395&page=411"
    }
}
