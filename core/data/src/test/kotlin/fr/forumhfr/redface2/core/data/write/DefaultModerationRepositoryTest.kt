package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ModerationAlertPageParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultModerationRepositoryTest {
    private val client = mockk<HfrClient>()
    private val parser = mockk<ModerationAlertPageParser>()
    private val repository = DefaultModerationRepository(client, parser, Dispatchers.Unconfined)
    private val form = ModerationAlertState.Form("modo.php?cat=23", "form-token", "referer")

    @Test
    fun `load send and join use the supplied IO dispatcher including parsing`() = runBlocking {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "moderation-io") }
            .asCoroutineDispatcher().use { dispatcher ->
                val onIo = DefaultModerationRepository(client, parser, dispatcher)
                coEvery { client.fetchModerationAlertPage(23, 35421, 2800456, 76) } coAnswers {
                    assertTrue(Thread.currentThread().name.startsWith("moderation-io"))
                    "load-response"
                }
                every { parser.parseState("load-response") } answers {
                    assertTrue(Thread.currentThread().name.startsWith("moderation-io"))
                    form
                }
                coEvery {
                    client.submitModerationAlert(form.action, form.hashCheck, form.refererPage, "Abus")
                } coAnswers {
                    assertTrue(Thread.currentThread().name.startsWith("moderation-io"))
                    "send-response"
                }
                coEvery { client.joinModerationAlert(form.action, form.hashCheck, form.refererPage) } coAnswers {
                    assertTrue(Thread.currentThread().name.startsWith("moderation-io"))
                    "join-response"
                }
                every { parser.parseOutcome("send-response") } answers {
                    assertTrue(Thread.currentThread().name.startsWith("moderation-io"))
                    ModerationAlertOutcome.Sent("envoyé")
                }
                every { parser.parseOutcome("join-response") } answers {
                    assertTrue(Thread.currentThread().name.startsWith("moderation-io"))
                    ModerationAlertOutcome.Joined("joint")
                }
                assertEquals(form, onIo.loadAlert(23, 35421, 2800456, 76))
                assertEquals(ModerationAlertOutcome.Sent("envoyé"), onIo.sendAlert(form, "Abus"))
                val prompt = ModerationAlertState.JoinPrompt(form.action, form.hashCheck, form.refererPage)
                assertEquals(ModerationAlertOutcome.Joined("joint"), onIo.joinAlert(prompt))
            }
    }

    @Test
    fun `blank reason never calls the client`() = runTest {
        assertTrue(repository.sendAlert(form, " \n\t") is ModerationAlertOutcome.Rejected)
        coVerify(exactly = 0) { client.submitModerationAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `network failure propagates without a retry`() = runTest {
        val error = IOException("offline")
        coEvery { client.submitModerationAlert(any(), any(), any(), any()) } throws error
        assertSame(error, runCatching { repository.sendAlert(form, "Abus") }.exceptionOrNull())
        coVerify(exactly = 1) { client.submitModerationAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `server rejection is preserved`() = runTest {
        coEvery { client.joinModerationAlert(any(), any(), any()) } returns "response"
        every { parser.parseOutcome("response") } returns ModerationAlertOutcome.Rejected("Refus")
        assertEquals(
            ModerationAlertOutcome.Rejected("Refus"),
            repository.joinAlert(ModerationAlertState.JoinPrompt(form.action, form.hashCheck, null)),
        )
    }
}
