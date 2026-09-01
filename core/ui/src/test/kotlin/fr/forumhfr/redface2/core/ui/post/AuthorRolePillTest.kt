package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthorRolePillTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders the five exact visible labels and their long role descriptions`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Column {
                    AuthorRolePill(AuthorRole.MODERATOR)
                    AuthorRolePill(AuthorRole.ADMIN)
                    AuthorRolePill(AuthorRole.SUPER_ADMIN)
                    AuthorRolePill(AuthorRole.DEVELOPER)
                    AuthorRolePill(AuthorRole.ARCHITECT)
                }
            }
        }

        listOf("Modérateur", "Admin", "SupAdmin", "Dev", "Architecte").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        }
        listOf(
            "Rôle : Modérateur",
            "Rôle : Administrateur",
            "Rôle : Super Administrateur",
            "Rôle : Développeur",
            "Rôle : Architecte / Développeur principal",
        ).forEach { description ->
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun `member emits no pill node`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                AuthorRolePill(
                    role = AuthorRole.MEMBER,
                    modifier = Modifier.testTag(MEMBER_TAG),
                )
            }
        }

        compose.onNodeWithTag(MEMBER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    private companion object {
        const val MEMBER_TAG = "AuthorRoleMemberPill"
    }
}
