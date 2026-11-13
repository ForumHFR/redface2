package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHostTest {
    private enum class Surface { Smileys, Options, Picker, Header, Recipients, Restore }
    private class DraftOfferConfig

    private val session = EditorSession("opening", "alice", "editor")
    private val memory = EditorHostMemory<Surface>()
    private val body = TextFieldState("Bonjour", TextRange(6, 1))
    private var state = EditorHostState(EditorScaffoldChrome(), EditorSubmissionState.Ready, true, true)
    private val calls = mutableListOf<Any?>()
    private var uncertainty: Pair<EventKey, EditorDraftStore.AttemptToken>? = null
    private val events = object : EditorHostEvents {
        override var state = EditorEventState(session)
        override fun dispatch(input: EditorEventInput) = reduceEditorEvents(state, input).also { state = it.state }
        override fun uncertainAttempt(key: EventKey) = uncertainty?.takeIf { it.first == key }?.second
    }
    private fun host(
        header: Surface? = null,
        onComplete: ((EditorCause) -> Unit)? = null,
        onAcknowledgeUncertain: (() -> Unit)? = { calls.add("acknowledge") },
        onAcknowledgeLocal: (() -> Unit)? = { calls.add("acknowledge local") },
        draftOffer: DraftOfferConfig? = null,
    ) = EditorHost(
        { state }, body, events,
        EditorHostActions({ calls.add(it) }, { calls.add(it) }, {
            calls.add("disarm"); state = state.copy(submission = EditorSubmissionState.Ready)
        }, { calls.add(it) }, {
            calls.add("preview")
            state = state.copy(scaffold = state.scaffold.copy(previewVisible = !state.scaffold.previewVisible))
        }, { calls.add("close") }, onComplete = onComplete, onAcknowledgeUncertain = onAcknowledgeUncertain,
            onAcknowledgeLocal = onAcknowledgeLocal),
        EditorHostSurfaces(Surface.Smileys, Surface.Options, Surface.Picker, header = header,
            // Simulate the old adapters' panel policy: the explicit offer field must take precedence.
            returnsToBody = { it != header && it != Surface.Restore },
            draftOffer = Surface.Restore.takeIf { draftOffer != null }),
        { calls.add("picker") }, { calls.add("smileys") }, memory,
    )
    private fun error(error: EditorError, sequence: Long = 1): EditorEvent {
        val event = EditorEvent(EventKey(session, OperationRef.Local(EditorLocalOperation.Validation, sequence), 0),
            EditorEventContent.Problem(error), sequence)
        events.dispatch(EditorEventInput.Received(event))
        return event
    }

    @Test fun settingsCloseOptionsBeforeNavigatingOnceAndNeverLaunchImages() {
        val host = host()
        host.barActions.onOptions()
        host.openSettings {
            assertNull(host.surface)
            assertTrue(host.consumeReturn())
            calls.add("settings")
        }
        host.openSettings { calls.add("duplicate") }
        assertEquals(listOf("settings"), calls)
        host.returnToBody { body.edit { selection = TextRange.Zero } }
        assertEquals(TextRange(6, 1), body.selection)
        assertEquals("Bonjour", body.text.toString())
    }

    @Test fun settingsRejectUploadAndDisabledControlsWithoutClosingOptions() {
        val host = host()
        host.barActions.onOptions()
        listOf(EditorScaffoldChrome(uploading = true), EditorScaffoldChrome(controlsEnabled = false)).forEach {
            state = state.copy(scaffold = it)
            host.openSettings { calls.add("settings") }
            assertEquals(Surface.Options, host.surface)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun settingsRejectAStaleOptionsTapWhileTheImagePickerIsOpen() {
        val host = host()
        host.barActions.onOptions()
        val staleTap = { host.openSettings { calls.add("settings") } }
        host.closeSurface()
        host.imageActions.onUpload()
        staleTap()
        assertEquals(Surface.Picker, host.surface)
        assertEquals(listOf("picker"), calls)
    }

    // #1417 point 5: « Formats » is a tab of the Options sheet, not another surface nor a Back level.
    @Test fun `ED22 ED80 a format from the formats tab applies once to the captured selection and closes`() {
        val host = host()
        host.barActions.onOptions()
        assertEquals(EditorOptionsTab.Options, host.optionsFormats.tab)
        host.optionsFormats.onTab(EditorOptionsTab.Formats)
        assertEquals(Surface.Options, host.surface)
        assertEquals(EditorOptionsTab.Formats, host.optionsFormats.tab)
        assertTrue(host.optionsFormats.enabled)
        // The field may collapse its selection while the sheet holds the focus.
        body.edit { selection = TextRange(3) }
        val staleTap = host.optionsFormats.onFormat
        staleTap(BbcodeAction.Bold)
        assertEquals(listOf<Any?>(BbcodeAction.Bold), calls)
        assertEquals(TextRange(6, 1), body.selection)
        assertNull(host.surface)
        // A second tap of the same composition, or of a retained one, finds no live tab.
        staleTap(BbcodeAction.Italic)
        host.formatFromOptions(BbcodeAction.Italic)
        assertEquals(listOf<Any?>(BbcodeAction.Bold), calls)
        assertTrue(host.consumeReturn())
        assertFalse(host.consumeReturn())
        host.returnToBody { body.edit { selection = TextRange.Zero } }
        assertEquals(TextRange(6, 1), body.selection)
    }

    @Test fun `ED22 a format from the formats tab keeps a selection made after an insertion changed the text`() {
        val host = host()
        host.barActions.onOptions()
        host.selectOptionsTab(EditorOptionsTab.Formats)
        body.edit { replace(length, length, " image"); selection = TextRange(length) }
        val inserted = body.selection
        host.formatFromOptions(BbcodeAction.Bold)
        assertEquals(listOf<Any?>(BbcodeAction.Bold), calls)
        assertEquals(inserted, body.selection)
    }

    @Test fun `ED44 information consumes a formats tap without changing the selection or closing the sheet`() {
        val host = host()
        host.barActions.onOptions()
        host.selectOptionsTab(EditorOptionsTab.Formats)
        error(EditorErrors.noImageReceived)
        body.edit { selection = TextRange(3) }
        host.formatFromOptions(BbcodeAction.Bold)
        assertNull(events.visibleEvent)
        assertTrue(calls.isEmpty())
        assertEquals("Bonjour", body.text.toString())
        assertEquals(TextRange(3), body.selection)
        assertEquals(Surface.Options, host.surface)
        assertEquals(EditorOptionsTab.Formats, host.optionsFormats.tab)
        assertFalse(host.consumeReturn())
        // The next gesture applies the format to the selection captured when Options opened.
        host.formatFromOptions(BbcodeAction.Bold)
        assertEquals(listOf<Any?>(BbcodeAction.Bold), calls)
        assertEquals(TextRange(6, 1), body.selection)
        assertNull(host.surface)
    }

    @Test fun `ED44 stale formats callbacks cannot consume information on another surface`() {
        val host = host()
        host.barActions.onOptions()
        host.selectOptionsTab(EditorOptionsTab.Formats)
        val stale = host.optionsFormats.onFormat
        host.closeSurface()
        host.imageActions.onUpload()
        val information = error(EditorErrors.noImageReceived)
        stale(BbcodeAction.Bold)
        assertEquals(information, events.visibleEvent)
        assertEquals(Surface.Picker, host.surface)
        assertEquals(listOf<Any?>("picker"), calls)
    }

    @Test fun `formats need the live formats tab and never apply from the options tab or another surface`() {
        val host = host()
        host.formatFromOptions(BbcodeAction.Bold)
        host.selectOptionsTab(EditorOptionsTab.Formats)
        assertEquals(EditorOptionsTab.Options, host.optionsFormats.tab)
        host.barActions.onOptions()
        host.formatFromOptions(BbcodeAction.Bold)
        assertEquals(Surface.Options, host.surface)
        host.selectOptionsTab(EditorOptionsTab.Formats)
        val staleTap = host.optionsFormats.onFormat
        val staleTab = host.optionsFormats.onTab
        host.closeSurface()
        host.imageActions.onUpload()
        staleTap(BbcodeAction.Bold)
        staleTab(EditorOptionsTab.Formats)
        assertEquals(Surface.Picker, host.surface)
        assertEquals(EditorOptionsTab.Options, host.optionsFormats.tab)
        assertEquals(listOf<Any?>("picker"), calls)
    }

    @Test fun `ED34 formats are disabled and rejected while armed uploading read-only or locked`() {
        val host = host()
        host.barActions.onOptions()
        host.selectOptionsTab(EditorOptionsTab.Formats)
        val ready = state
        listOf(
            ready.copy(submission = EditorSubmissionState.Armed(ArmToken(1, session, 0, 4_000L))),
            ready.copy(scaffold = EditorScaffoldChrome(uploading = true)),
            ready.copy(scaffold = EditorScaffoldChrome(bodyReadOnly = true)),
            ready.copy(scaffold = EditorScaffoldChrome(controlsEnabled = false)),
        ).forEach { locked ->
            state = locked
            assertEquals(EditorOptionsTab.Formats, host.optionsFormats.tab)
            assertFalse(host.optionsFormats.enabled)
            host.formatFromOptions(BbcodeAction.Bold)
            assertEquals(Surface.Options, host.surface)
            // The list stays readable: changing tab is never locked.
            host.selectOptionsTab(EditorOptionsTab.Options)
            host.selectOptionsTab(EditorOptionsTab.Formats)
        }
        assertTrue(calls.isEmpty())
        state = ready
        assertTrue(host.optionsFormats.enabled)
        host.formatFromOptions(BbcodeAction.Bold)
        assertEquals(listOf<Any?>(BbcodeAction.Bold), calls)
    }

    @Test fun `ED85 ED86 the formats tab survives rotation and Back closes the sheet in one gesture`() {
        val initial = host()
        initial.barActions.onOptions()
        initial.selectOptionsTab(EditorOptionsTab.Formats)
        val rotated = host()
        assertEquals(Surface.Options, rotated.surface)
        assertEquals(EditorOptionsTab.Formats, rotated.optionsFormats.tab)
        val layers = mutableListOf<EditorBackAction>()
        rotated.back(onLayerBack = layers::add)
        assertNull(rotated.surface)
        assertEquals(listOf<EditorBackAction>(EditorBackAction.CloseSheet(EditorSheet.Options)), layers)
        assertTrue(rotated.consumeReturn())
        assertTrue(calls.isEmpty())
    }

    @Test fun `the options sheet always reopens on the options tab`() {
        val host = host()
        val closings = listOf<() -> Unit>(
            { host.closeSurface() }, { host.back() }, { host.openSettings {} },
            { host.formatFromOptions(BbcodeAction.Bold) }, { memory.reset() },
        )
        closings.forEach { close ->
            host.barActions.onOptions()
            host.selectOptionsTab(EditorOptionsTab.Formats)
            close()
            assertNull(host.surface)
            host.barActions.onOptions()
            assertEquals(EditorOptionsTab.Options, host.optionsFormats.tab)
            host.closeSurface()
        }
    }

    // v0.7.8 ED-40/44: the offer has one entry, with no information exception.
    @Test fun `offer status closes at sheet rank before disarming and returns selection once`() {
        val initial = host(draftOffer = DraftOfferConfig())
        initial.open(Surface.Restore)
        val arm = ArmToken(1, session, 0, 4_000L)
        state = state.copy(submission = EditorSubmissionState.Armed(arm))
        val rotated = host(draftOffer = DraftOfferConfig())
        assertTrue(rotated.draftOfferOpen)
        val layers = mutableListOf<EditorBackAction>()
        rotated.back(onLayerBack = layers::add)
        assertEquals(listOf(EditorBackAction.CloseSheet(EditorSheet.DraftOffer)), layers)
        assertFalse(rotated.draftOfferOpen)
        assertTrue(rotated.consumeReturn())
        assertFalse(initial.consumeReturn())
        rotated.returnToBody { body.edit { selection = TextRange.Zero } }
        assertEquals(TextRange(6, 1), body.selection)
        assertEquals(EditorSubmissionState.Armed(arm), state.submission)
        assertTrue(calls.isEmpty())
    }

    @Test fun `ordinary information consumes the first offer gesture without any decision`() {
        val host = host(draftOffer = DraftOfferConfig())
        error(EditorErrors.noImageReceived)
        host.open(Surface.Restore)
        assertFalse(host.draftOfferOpen)
        assertNull(events.visibleEvent)
        host.open(Surface.Restore)
        assertTrue(host.draftOfferOpen)
        assertTrue(calls.isEmpty())
    }

    // ED-44/45 (kept): without the offer capability, information still consumes the gesture and an
    // ordinary detail keeps its panel focus ownership, with no deferred sheet return.
    @Test fun `offer capability disabled keeps ED44 and ordinary detail focus ownership`() {
        val host = host()
        val information = error(EditorErrors.noImageReceived)
        host.open(Surface.Restore)
        assertNull(host.surface)
        assertNull(events.state.visibleEvent)
        val problem = error(EditorErrors.network, 2)
        host.barActions.onEvent(problem.key, EditorEventAction.Details)
        host.closeDetails(information.key)
        assertEquals(problem, host.detail)
        host.closeDetails(problem.key)
        assertFalse(host.consumeReturn()) // EditorHeaderPanel remains the owner for ordinary details.
        assertFalse(host.draftOfferOpen)
    }

    @Test fun `ED34 retained callbacks capture their arm and use current eligibility`() {
        val host = host()
        val token = ArmToken(1, session, 0, 4_000L)
        state = state.copy(submission = EditorSubmissionState.Armed(token))
        val retained = host.submitActions
        state = state.copy(submission = EditorSubmissionState.Armed(token.copy(id = 2)))
        retained.onConfirmSubmit()
        assertEquals(listOf(token), calls)
        state = state.copy(postAllowed = false, sendActivatable = false)
        retained.onConfirmSubmit(); retained.onSubmit()
        assertEquals(listOf(token), calls)
    }

    @Test fun `ED44 the gesture clearing information cannot open or arm`() {
        val host = host()
        error(EditorErrors.noImageReceived)
        host.barActions.onSmileys()
        assertNull(host.surface)
        assertTrue(calls.isEmpty())
        error(EditorErrors.noImageReceived, sequence = 2)
        host.submitActions.onSubmit()
        assertTrue(calls.isEmpty())
        host.submitActions.onSubmitWithTimeout!!(8_000L)
        assertEquals(listOf(8_000L), calls)
    }

    @Test fun `optional header and status support topic reply MP and new MP without variants`() {
        val reply = host()
        assertNull(reply.onOpenHeader)
        assertNull(reply.imageActions.onInsertUrl)
        val recipients = EditorAppBarStatus(R.drawable.ic_editor_recipients, "2 destinataires",
            { reply.open(Surface.Recipients) })
        assertSame(recipients, reply.scaffoldChrome(firstStatus = recipients).firstStatus)
        recipients.onClick()
        assertEquals(Surface.Recipients, reply.surface)
        reply.back()
        assertNull(reply.surface)
        val withHeader = host(Surface.Header)
        withHeader.onOpenHeader!!()
        assertEquals(Surface.Header, withHeader.surface)
        val generation = withHeader.returnGeneration
        withHeader.back()
        assertEquals(generation, withHeader.returnGeneration) // The header overlay restores focus itself.
        assertTrue(calls.isEmpty())
        state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = false))
        withHeader.closeSurface()
        withHeader.onOpenHeader!!()
        assertNull(withHeader.surface)
    }

    @Test fun `Complete forwards the cause to its port with or without a header and requires enabled controls`() {
        val cases = listOf(null to EditorErrors.unknownRecipient, Surface.Header to EditorErrors.missingTitle)
        cases.forEachIndexed { index, (header, problem) ->
            val host = host(header, onComplete = { calls.add(it) })
            val event = error(problem, sequence = index.toLong() + 1)
            val previousCalls = calls.toList()
            assertEquals(EditorEventAction.Complete, host.scaffoldChrome().event!!.action)
            state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = false))
            host.barActions.onEvent(event.key, EditorEventAction.Complete)
            assertEquals(event, events.state.visibleEvent)
            assertEquals(previousCalls, calls)
            assertNull(host.surface)
            state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = true))
            host.barActions.onEvent(event.key, EditorEventAction.Complete)
            assertEquals(previousCalls + problem.cause, calls)
            assertNull(host.surface)
            assertNull(host.detail)
            assertNull(events.state.visibleEvent)
            host.barActions.onEvent(event.key, EditorEventAction.Complete)
            assertEquals(previousCalls + problem.cause, calls)
        }
    }

    @Test fun `Complete without a port opens the header only when controls are enabled`() {
        val host = host(Surface.Header)
        val event = error(EditorErrors.missingSubcategory)
        state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = false))
        host.barActions.onEvent(event.key, EditorEventAction.Complete)
        assertNull(host.surface)
        assertEquals(event, events.state.visibleEvent)
        state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = true))
        host.barActions.onEvent(event.key, EditorEventAction.Complete)
        assertEquals(Surface.Header, host.surface)
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `Complete without a port or header falls back to details and can be acknowledged`() {
        val host = host()
        val event = error(EditorErrors.unknownRecipient)
        val displayed = host.scaffoldChrome().event!!
        assertEquals(event.key, displayed.key)
        assertEquals(event.summary, displayed.summary)
        assertEquals(event.detail, displayed.detail)
        assertEquals(EditorEventAction.Details, displayed.action)
        assertEquals(EditorEventAction.Complete, events.state.visibleEvent!!.action)
        state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = false))
        host.barActions.onEvent(displayed.key, displayed.action!!)
        assertEquals(displayed, host.detail)
        assertEquals(event.key, events.state.detailKey)
        assertEquals(event, events.state.visibleEvent)
        assertNull(host.surface)
        host.closeDetails(event.key)
        assertNull(host.detail)
        assertNull(events.state.detailKey)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `ED45 retained detail projection acknowledges the current event by key`() {
        val host = host()
        val event = error(EditorErrors.network)
        host.barActions.onEvent(event.key, EditorEventAction.Details)
        memory.detail = event.copy(arrivalOrder = event.arrivalOrder + 1)
        assertNotEquals(event, host.detail)
        host.closeDetails(event.key)
        assertNull(host.detail)
        assertNull(events.state.detailKey)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `Complete without a port header or detail falls back to dismissal`() {
        val host = host()
        val event = error(EditorErrors.missingRecipients)
        val displayed = host.scaffoldChrome().event!!
        assertEquals(event.key, displayed.key)
        assertEquals(EditorEventAction.Dismiss, displayed.action)
        state = state.copy(scaffold = state.scaffold.copy(controlsEnabled = false))
        host.barActions.onEvent(displayed.key, displayed.action!!)
        assertNull(host.surface)
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `ED53 ED86 rotation retains selection IME and a single deferred return`() {
        val initial = host()
        initial.barActions.onOptions()
        memory.focus.imeBeforeSurface = true
        memory.focus.autoFocusConsumed = true
        val rotated = host()
        assertEquals(Surface.Options, rotated.surface)
        assertSame(memory.focus, rotated.focusMemory)
        assertTrue(rotated.focusMemory.imeBeforeSurface)
        assertTrue(rotated.focusMemory.autoFocusConsumed)
        rotated.closeSurface()
        assertTrue(rotated.consumeReturn())
        assertFalse(initial.consumeReturn())
        rotated.returnToBody { body.edit { selection = TextRange.Zero } }
        assertEquals(TextRange(6, 1), body.selection)
        rotated.barActions.onOptions()
        body.edit { replace(length, length, " image"); selection = TextRange(length) }
        val inserted = body.selection
        rotated.closeSurface()
        rotated.returnToBody { body.edit { selection = TextRange.Zero } }
        assertEquals(inserted, body.selection)
        memory.reset()
        assertNull(rotated.surface)
        assertFalse(rotated.focusMemory.imeBeforeSurface)
        assertFalse(rotated.focusMemory.autoFocusConsumed)
        assertFalse(rotated.consumeReturn())
    }

    @Test fun `ED85 Back closes detail then disarms then mode then initial header before leaving`() {
        val host = host(Surface.Header)
        memory.surface = Surface.Header // Open on entry without a body/IME gesture (ED-101/113).
        val event = error(EditorErrors.network)
        host.barActions.onEvent(event.key, EditorEventAction.Details)
        memory.bar = EditorBarState(EditorBarMode.Color)
        state = state.copy(submission = EditorSubmissionState.Armed(ArmToken(1, session, 0, 4_000L)))
        host.back()
        assertNull(host.detail)
        assertTrue(calls.isEmpty())
        assertEquals(EditorBarMode.Color, memory.bar.mode)
        host.back()
        assertEquals(listOf("disarm"), calls)
        host.back()
        assertEquals(EditorBarMode.Format, memory.bar.mode)
        host.back(); host.back()
        assertNull(host.surface)
        assertEquals(listOf("disarm"), calls)
        host.back()
        assertEquals(listOf("disarm", "close"), calls)
    }

    @Test fun `ED85 Back closes preview without consuming displayed information or its masked mode`() {
        val host = host()
        state = state.copy(scaffold = state.scaffold.copy(previewVisible = true))
        memory.bar = EditorBarState(EditorBarMode.Format)
        val information = error(EditorErrors.noImageReceived)

        host.back()

        assertEquals(listOf("preview"), calls)
        assertFalse(state.scaffold.previewVisible)
        assertEquals(EditorBarMode.Format, memory.bar.mode)
        assertEquals(information, events.state.visibleEvent)
        host.back()
        assertEquals(listOf("preview", "close"), calls)
        assertEquals(information, events.state.visibleEvent)
        assertEquals(EditorBarMode.Format, memory.bar.mode)
    }

    @Test fun `ED85 event without detail skips the masked mode and native layers win`() {
        val host = host()
        error(EditorErrors.network)
        memory.bar = EditorBarState(EditorBarMode.Format)
        val layers = mutableListOf<EditorBackAction>()
        host.back(EditorBackState(sheet = EditorSheet.Smileys, smileyDetailOpen = true), layers::add)
        host.back(EditorBackState(tooltipOpen = true), layers::add)
        assertEquals(listOf(EditorBackAction.ReturnToSmileyGrid, EditorBackAction.CloseTooltip), layers)
        assertEquals(EditorBarMode.Format, memory.bar.mode)
        assertTrue(calls.isEmpty())
        // 22:12: Format is masked by the displayed event: Back skips it (not closed, not acknowledged) and leaves.
        host.back()
        assertEquals(EditorBarMode.Format, memory.bar.mode)
        assertEquals(listOf<Any?>("close"), calls)
        assertEquals(EditorErrors.network, (events.state.visibleEvent!!.content as EditorEventContent.Problem).error)
    }

    @Test fun `ED45 ED170 only an explicit Masquer of the bound delivery acknowledges its current attempt`() {
        val host = host()
        val attempt = EditorDraftStore.AttemptToken("alice", session.key, "attempt", 1)
        state = state.copy(submission = EditorSubmissionState.Uncertain(attempt))
        fun bound(sequence: Long) = error(EditorErrors.uncertain, sequence).also {
            uncertainty = it.key to attempt
            host.barActions.onEvent(it.key, EditorEventAction.Details)
            assertEquals(it, host.detail)
        }
        // Back and closing the overlay are visual acknowledgements only (ED-85).
        bound(1)
        host.back()
        val closed = bound(2)
        host.closeDetails(closed.key)
        assertNull(host.detail)
        assertTrue(calls.isEmpty())
        // A bound delivery cannot borrow a different or an accepted attempt.
        listOf(EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            EditorSubmissionState.Uncertain(attempt, accepted = true)).forEachIndexed { index, newer ->
            val retained = bound(3L + index)
            state = state.copy(submission = newer)
            host.dismissDetails(retained.key)
            assertNull(host.detail)
            assertTrue("attempt guard $index", calls.isEmpty())
            state = state.copy(submission = EditorSubmissionState.Uncertain(attempt))
        }
        val current = bound(5)
        host.dismissDetails(closed.key)
        assertEquals(current, host.detail)
        assertTrue(calls.isEmpty())
        host.dismissDetails(current.key)
        assertEquals(listOf<Any?>("acknowledge"), calls)
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        host.dismissDetails(current.key)
        assertEquals(listOf<Any?>("acknowledge"), calls)
    }

    @Test fun boundUncertaintyWithoutDetailAcknowledgesOnceFromBar() {
        val host = host()
        val attempt = EditorDraftStore.AttemptToken("alice", session.key, "attempt", 1)
        state = state.copy(submission = EditorSubmissionState.Uncertain(attempt))
        // The catalogue always supplies a detail; construct the host's Dismiss route explicitly.
        val event = error(EditorErrors.uncertain.copy(detail = null, action = EditorErrorAction.Dismiss))
        uncertainty = event.key to attempt
        val displayed = host.scaffoldChrome().event!!
        assertEquals(event.key, displayed.key)
        assertNull(displayed.detail)
        assertEquals(EditorEventAction.Dismiss, displayed.action)
        host.barActions.onEvent(displayed.key, displayed.action!!)
        assertEquals(listOf("acknowledge"), calls)
        assertNull(events.state.visibleEvent)
        assertNull(host.detail)
        host.barActions.onEvent(displayed.key, displayed.action!!)
        assertEquals(listOf("acknowledge"), calls)
    }

    @Test fun unboundOrReplacedUncertaintyWithoutDetailDoesNotAcknowledge() {
        val host = host()
        val attempt = EditorDraftStore.AttemptToken("alice", session.key, "attempt", 1)
        val cases = listOf(
            null to EditorSubmissionState.Uncertain(attempt),
            attempt to EditorSubmissionState.Uncertain(attempt.copy(id = "replacement")),
        )
        cases.forEachIndexed { index, (bound, current) ->
            state = state.copy(submission = current)
            val event = error(
                EditorErrors.uncertain.copy(detail = null, action = EditorErrorAction.Dismiss), index + 1L,
            )
            uncertainty = bound?.let { event.key to it }
            val displayed = host.scaffoldChrome().event!!
            assertEquals(event.key, displayed.key)
            assertNull(displayed.detail)
            assertEquals(EditorEventAction.Dismiss, displayed.action)
            host.barActions.onEvent(displayed.key, displayed.action!!)
            assertNull(events.state.visibleEvent)
            assertNull(host.detail)
            assertTrue("attempt guard $index", calls.isEmpty())
        }
    }

    @Test fun `ED45 without an acknowledgement port Masquer stays visual`() {
        val host = host(onAcknowledgeUncertain = null)
        val attempt = EditorDraftStore.AttemptToken("alice", session.key, "attempt", 1)
        state = state.copy(submission = EditorSubmissionState.Uncertain(attempt))
        val event = error(EditorErrors.uncertain)
        uncertainty = event.key to attempt
        host.barActions.onEvent(event.key, EditorEventAction.Details)
        host.dismissDetails(event.key)
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `ED45 local attempt failure Masquer calls only the local port once in Ready and Armed`() {
        val host = host()
        val armed = EditorSubmissionState.Armed(ArmToken(1, session, 0, 4_000L))
        listOf(EditorSubmissionState.Ready, armed).forEachIndexed { index, submission ->
            state = state.copy(submission = submission)
            val event = error(EditorErrors.draftWriteFailed, index + 1L)
            assertEquals(EditorEventAction.Details, host.scaffoldChrome().event!!.action)
            // The bar cannot dismiss a delivery whose current action opens Details.
            host.barActions.onEvent(event.key, EditorEventAction.Dismiss)
            assertEquals(index, calls.size)
            host.barActions.onEvent(event.key, EditorEventAction.Details)
            assertEquals(event, host.detail)
            assertEquals(index, calls.size)
            host.dismissDetails(event.key)
            host.dismissDetails(event.key)
            assertEquals(List(index + 1) { "acknowledge local" }, calls)
            assertEquals(submission, state.submission)
            assertNull(host.detail)
            assertNull(events.state.visibleEvent)
        }
    }

    @Test fun `ED45 local attempt failure without detail also routes bar Masquer once`() {
        val host = host()
        // The catalogue currently always supplies a detail; exercise the host's Dismiss route explicitly.
        val event = error(EditorErrors.draftWriteFailed.copy(detail = null, action = EditorErrorAction.Dismiss))
        assertEquals(EditorEventAction.Dismiss, host.scaffoldChrome().event!!.action)
        host.barActions.onEvent(event.key, EditorEventAction.Dismiss)
        host.barActions.onEvent(event.key, EditorEventAction.Dismiss)
        assertEquals(listOf("acknowledge local"), calls)
        assertNull(events.state.visibleEvent)
    }

    @Test fun `ED85 Back and closeDetails never acknowledge a local attempt write failure`() {
        val host = host()
        val first = error(EditorErrors.draftWriteFailed)
        host.barActions.onEvent(first.key, EditorEventAction.Details)
        host.back()
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        val second = error(EditorErrors.draftWriteFailed, 2)
        host.barActions.onEvent(second.key, EditorEventAction.Details)
        host.closeDetails(second.key)
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `ED45 stale local failure keys cannot call either acknowledgement port`() {
        val host = host()
        val stale = error(EditorErrors.draftWriteFailed)
        host.barActions.onEvent(stale.key, EditorEventAction.Details)
        events.dispatch(EditorEventInput.Acknowledge(stale.key))
        // The overlay snapshot still exists, but its delivery is no longer current.
        host.dismissDetails(stale.key)
        assertNull(host.detail)
        assertTrue(calls.isEmpty())
        val current = error(EditorErrors.draftWriteFailed, 2)
        host.barActions.onEvent(stale.key, EditorEventAction.Dismiss)
        assertEquals(current, events.state.visibleEvent)
        host.barActions.onEvent(current.key, EditorEventAction.Details)
        host.dismissDetails(stale.key)
        assertEquals(current, host.detail)
        assertEquals(current, events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }

    @Test fun `ED45 Masquer of another cause never calls the local acknowledgement port`() {
        val host = host()
        listOf(EditorErrors.network, EditorErrors.draftSaveFailed, EditorErrors.uncertain)
            .forEachIndexed { index, problem ->
                val event = error(problem, index + 1L)
                host.barActions.onEvent(event.key, EditorEventAction.Details)
                host.dismissDetails(event.key)
                assertNull(host.detail)
                assertNull(events.state.visibleEvent)
                assertTrue(calls.isEmpty())
            }
    }

    @Test fun `ED45 without the local port attempt failure Masquer stays visual on detail and bar`() {
        val host = host(onAcknowledgeLocal = null)
        val detail = error(EditorErrors.draftWriteFailed)
        host.barActions.onEvent(detail.key, EditorEventAction.Details)
        host.dismissDetails(detail.key)
        assertNull(host.detail)
        assertNull(events.state.visibleEvent)
        val bar = error(EditorErrors.draftWriteFailed.copy(detail = null, action = EditorErrorAction.Dismiss), 2)
        host.barActions.onEvent(bar.key, EditorEventAction.Dismiss)
        assertNull(events.state.visibleEvent)
        assertTrue(calls.isEmpty())
    }
}
