package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftRetentionPurgerTest {

    private val dao = mockk<EditorDraftDao>(relaxed = true)
    private val now = Instant.ofEpochMilli(1_700_000_000_000L)
    private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)

    private val purger = DraftRetentionPurger(
        editorDraftDao = dao,
        clock = fixedClock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `purge drops drafts older than the 30-day retention from the clock`() = runTest {
        purger.purge().join()

        val expectedCutoff = now.toEpochMilli() - Duration.ofDays(30).toMillis()
        coVerify { dao.deleteOlderThan(expectedCutoff) }
    }
}
