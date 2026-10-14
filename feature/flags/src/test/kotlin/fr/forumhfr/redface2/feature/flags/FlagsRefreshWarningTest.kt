package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlagsRefreshWarningTest {
    @Test
    fun `warning offers retry and reports the action once`() = runTest {
        val host = SnackbarHostState()
        val shown = async { host.showRefreshWarning("Liste incomplète", "Réessayer") }
        runCurrent()
        assertEquals("Liste incomplète", host.currentSnackbarData?.visuals?.message)
        assertEquals("Réessayer", host.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        host.currentSnackbarData?.performAction()
        assertEquals(SnackbarResult.ActionPerformed, shown.await())
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `cancelling an old account warning removes its snackbar`() = runTest {
        val host = SnackbarHostState()
        val shown = async { host.showRefreshWarning("Liste incomplète", "Réessayer") }
        runCurrent()
        shown.cancel()
        shown.join()
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `category labels use at most two names then the remainder with a numeric fallback`() {
        val issue = FlagRefreshIssue(stickyCategories = listOf(
            FlagRefreshCategory(1, "Programmation"), FlagRefreshCategory(2, null),
            FlagRefreshCategory(3, "Discussions"), FlagRefreshCategory(4, "Hardware"),
        ))
        assertEquals("Programmation, Catégorie 2 +2", issue.stickyCategoryLabels { "Catégorie $it" })
    }
}
