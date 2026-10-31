package fr.forumhfr.redface2.feature.profile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises both production entry points and their DisposableEffects across a real recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ProfileConfigurationRecreationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val repository = mockk<ProfileRepository>()
    private val response = CompletableDeferred<Result<List<String>>>()
    private var requestJob: Job? = null

    @Before
    fun registerHostAndRepositories() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, ProfileConfigurationHostActivity::class.java))
        val profile = UserProfile(
            userId = 54596,
            pseudo = "XaTriX",
            avatarUrl = null,
            registeredAt = null,
            postCount = null,
            location = null,
            signatureText = null,
            hasHardwareConfiguration = true,
        )
        coEvery { repository.getProfile(54596) } returns Result.success(profile)
        coEvery { repository.getUserConfiguration("XaTriX") } coAnswers {
            requestJob = currentCoroutineContext()[Job]
            response.await()
        }
        val blacklist = mockk<BlacklistRepository> {
            every { observeBlockedCanonicals() } returns MutableStateFlow(emptySet())
        }
        val auth = mockk<AuthRepository> {
            every { observeAuthState() } returns MutableStateFlow(AuthState.Anonymous)
        }
        ProfileConfigurationHostActivity.factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(
                profileRepository = repository,
                blacklistRepository = blacklist,
                authRepository = auth,
                userId = profile.userId,
                pseudoHint = profile.pseudo,
                avatarUrlHint = null,
            ) as T
        }
    }

    @After
    fun clearHostFactory() {
        ProfileConfigurationHostActivity.factory = null
    }

    @Test
    fun `card keeps loaded configuration through activity recreation without another request`() {
        assertConfigurationSurvivesRecreation(fullPage = false, loading = false)
    }

    @Test
    fun `card keeps the same pending request through activity recreation and displays its result`() {
        assertConfigurationSurvivesRecreation(fullPage = false, loading = true)
    }

    @Test
    fun `full profile keeps loaded configuration through activity recreation without another request`() {
        assertConfigurationSurvivesRecreation(fullPage = true, loading = false)
    }

    @Test
    fun `full profile keeps the same pending request through activity recreation and displays its result`() {
        assertConfigurationSurvivesRecreation(fullPage = true, loading = true)
    }

    @Test
    fun `removing the card cancels configuration while its activity and ViewModel remain alive`() {
        launchHost(fullPage = false).use { scenario ->
            openConfiguration()
            val pendingJob = requireNotNull(requestJob)
            lateinit var retained: ProfileViewModel
            scenario.onActivity {
                retained = it.profileViewModel
                // The app shell can remove the card without destroying the Activity-scoped VM.
                it.profileVisible = false
            }
            compose.waitForIdle()

            assertTrue("a real card closure must cancel the pending request", pendingJob.isCancelled)
            assertNull(retained.state.value.configurationSheet)
            compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
            compose.onNodeWithTag("profile_preview_sheet").assertDoesNotExist()
            scenario.onActivity {
                assertFalse("cancellation must come from disposal, not Activity destruction", it.isDestroyed)
                assertFalse(it.isChangingConfigurations)
                assertSame(retained, it.profileViewModel)
                it.profileVisible = true
            }
            compose.waitForIdle()

            compose.onNodeWithTag("profile_preview_sheet").assertExists()
            compose.onNodeWithTag("profile_configuration_sheet").assertDoesNotExist()
            coVerify(exactly = 1) { repository.getUserConfiguration("XaTriX") }
            compose.runOnIdle { response.complete(Result.success(LINES)) }
            compose.onNodeWithText(LINES.joinToString("\n")).assertDoesNotExist()
            assertNull(retained.state.value.configurationSheet)
        }
    }

    private fun assertConfigurationSurvivesRecreation(fullPage: Boolean, loading: Boolean) {
        if (!loading) response.complete(Result.success(LINES))
        launchHost(fullPage).use { scenario ->
            openConfiguration()
            lateinit var original: ProfileConfigurationHostActivity
            lateinit var retained: ProfileViewModel
            scenario.onActivity {
                original = it
                retained = it.profileViewModel
            }
            val originalJob = requireNotNull(requestJob)
            val before = retained.state.value.configurationSheet
            if (loading) {
                assertEquals(ProfileUiState.ConfigurationSheet.Loading, before)
                compose.onNodeWithContentDescription(LOADING).assertIsDisplayed()
                assertTrue(originalJob.isActive)
            } else {
                assertEquals(ProfileUiState.ConfigurationSheet.Loaded(LINES), before)
                compose.onNodeWithText(LINES.joinToString("\n")).assertIsDisplayed()
            }

            scenario.recreate()
            compose.waitForIdle()

            scenario.onActivity {
                assertNotSame("the Activity and composition must really be recreated", original, it)
                assertTrue(
                    "disposal must have happened during a configuration change", original.isChangingConfigurations,
                )
                assertSame("use the retained ViewModel, not a fake UI state", retained, it.profileViewModel)
            }
            compose.onNodeWithTag("profile_configuration_sheet").assertExists()
            assertEquals("recreation must preserve sheet state", before, retained.state.value.configurationSheet)
            assertSame("recreation must not replace the request", originalJob, requestJob)
            assertFalse("recreation must not cancel the request", originalJob.isCancelled)
            if (loading) {
                compose.onNodeWithContentDescription(LOADING).assertIsDisplayed()
                assertTrue(originalJob.isActive)
                compose.runOnIdle { response.complete(Result.success(LINES)) }
            }
            compose.onNodeWithText(LINES.joinToString("\n")).assertIsDisplayed()
            compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()
            assertEquals(ProfileUiState.ConfigurationSheet.Loaded(LINES), retained.state.value.configurationSheet)
            coVerify(exactly = 1) { repository.getUserConfiguration("XaTriX") }
            coVerify(exactly = 1) { repository.getProfile(54596) }
        }
    }

    private fun launchHost(fullPage: Boolean): ActivityScenario<ProfileConfigurationHostActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ProfileConfigurationHostActivity::class.java)
            .putExtra("fullPage", fullPage)
        return ActivityScenario.launch<ProfileConfigurationHostActivity>(intent).also { compose.waitForIdle() }
    }

    private fun openConfiguration() {
        compose.onNodeWithText("Configuration matérielle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("profile_configuration_sheet").assertExists()
    }

    private companion object {
        const val LOADING = "Chargement de la configuration matérielle"
        val LINES = listOf("P8Z77-V / i7 3770K / 32GB", "Technicolor TG788vn")
    }
}

/**
 * ActivityScenario owns the Activity; only the repository factory is supplied by the test.
 * An Activity-owned ViewModelProvider retains the real VM, including when testing the full route;
 * the app uses a Nav3 entry owner there, with the same configuration-change retention contract.
 * Calling the production entry points ensures their lifecycle effects are exercised too.
 */
internal class ProfileConfigurationHostActivity : ComponentActivity() {
    var profileVisible by mutableStateOf(true)
    lateinit var profileViewModel: ProfileViewModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileViewModel = ViewModelProvider(this, requireNotNull(factory))
            .get("profile-54596", ProfileViewModel::class.java)
        setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                if (profileVisible) {
                    if (intent.getBooleanExtra("fullPage", false)) {
                        ProfileRoute(
                            userId = 54596,
                            pseudoHint = "XaTriX",
                            avatarUrlHint = null,
                            onBack = { profileVisible = false },
                            onShowUserPosts = {},
                            viewModel = profileViewModel,
                        )
                    } else {
                        ProfilePreviewSheet(
                            userId = 54596,
                            pseudoHint = "XaTriX",
                            avatarUrlHint = null,
                            onDismiss = { profileVisible = false },
                            onOpenFullProfile = { _, _, _ -> profileVisible = false },
                            viewModel = profileViewModel,
                        )
                    }
                }
            }
        }
    }

    companion object {
        var factory: ViewModelProvider.Factory? = null
    }
}
