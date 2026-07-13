package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.domain.topic.NoTopicSearchResultsException
import fr.forumhfr.redface2.core.domain.topic.TopicPageEmission
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import fr.forumhfr.redface2.core.model.TopicSearchForm
import fr.forumhfr.redface2.core.model.TopicSearchRequest
import fr.forumhfr.redface2.core.model.search.SearchRequest
import fr.forumhfr.redface2.core.model.search.SearchResultPage
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `flow emitting one topic exposes loaded state with derived available pages`() = runTest {
        val topic = fakeTopic(page = 2, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(topic, mode.topic)
            assertEquals(listOf(1, 2, 3, 4, 5), loaded.availablePages)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)), repository.calls)
        assertEquals(
            "anonymous prefetch should fire on page+1 once the topic is loaded",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.prefetches,
        )
    }

    @Test
    fun `prefetch is cancelled when the screen is left (issue #108)`() = runTest {
        // Symmetric to CategoryViewModelTest's same-named case. Issue #108's
        // acceptance criterion is "le prefetch est annulé quand l'utilisateur quitte
        // la page". The topic-page prefetch is the more user-visible path of the two,
        // so it deserves its own pin — without this test, only the listing path
        // proves the cancellation chain. We suspend `prefetch` on awaitCancellation
        // and verify the in-flight call observes [CancellationException] when the
        // ViewModelStore is cleared (the public API the framework calls on screen
        // leave).
        val cancelObserved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val topic = fakeTopic(page = 2, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))
        repository.prefetchHook = { _, _, _ ->
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelObserved.complete(Unit)
                throw cancellation
            }
        }
        val vm = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        vm.state.test {
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "prefetch should have been launched on Loaded(page=2)",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.prefetches,
        )

        val store = androidx.lifecycle.ViewModelStore()
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        ).get(TopicViewModel::class.java)
        store.clear()

        kotlinx.coroutines.withTimeout(CANCEL_TIMEOUT_MS) {
            cancelObserved.await()
        }
    }

    @Test
    fun `last page does not trigger an out-of-range prefetch`() = runTest {
        val topic = fakeTopic(page = 5, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        topicViewModel(
            request = topicRequest(page = 5),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        ).state.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("no prefetch on the final page", repository.prefetches.isEmpty())
    }


    @Test
    fun `cache emission lands before fresh emission in observable state sequence`() = runTest {
        val cached = fakeTopic(page = 1, totalPages = 3, title = "cached")
        val fresh = fakeTopic(page = 1, totalPages = 3, title = "fresh")
        // SharedFlow with no replay so we control exactly when each emission lands and can
        // observe the sequence Loading → Loaded(cached) → Loaded(fresh) with Turbine instead
        // of asserting only the final state (which would pass even if cache-first was broken).
        val controlled = MutableSharedFlow<Topic>(replay = 0)
        val repository = FakeStreamingTopicRepository(controlled)

        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loading>(awaitItem())

            controlled.emit(cached)
            val cachedState = awaitItem()
            assertEquals(cached, assertMode<TopicUiState.Mode.Loaded>(cachedState).topic)

            controlled.emit(fresh)
            val freshState = awaitItem()
            assertEquals(fresh, assertMode<TopicUiState.Mode.Loaded>(freshState).topic)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `#877 provisional cache emission is exposed then settles on the network emission`() = runTest {
        val cached = fakeTopic(page = 1, totalPages = 3, title = "cached")
        val fresh = fakeTopic(page = 1, totalPages = 5, title = "fresh")
        val controlled = MutableSharedFlow<TopicPageEmission>(replay = 0)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeStreamingEmissionTopicRepository(controlled),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loading>(awaitItem())

            controlled.emit(TopicPageEmission(cached, provisional = true))
            val provisional = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            assertEquals("cached", provisional.topic.title)
            assertTrue("the cache emission must surface as provisional", provisional.provisional)

            controlled.emit(TopicPageEmission(fresh, provisional = false))
            val settled = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            assertEquals("fresh", settled.topic.title)
            assertFalse("the network emission must settle the page", settled.provisional)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `#877 live blacklist re-filter preserves the provisional flag`() = runTest {
        // Gate finding : loadedMode() defaults provisional=false, and the independent blacklist
        // collector rebuilds Mode.Loaded from the page ON SCREEN. Landing between the provisional
        // cache emission and the settled one, it must carry the provenance over — not fake-settle.
        val cached = fakeTopic(
            page = 1,
            totalPages = 3,
            title = "cached",
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val controlled = MutableSharedFlow<TopicPageEmission>(replay = 0)
        val blacklist = FakeBlacklistRepository()
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeStreamingEmissionTopicRepository(controlled),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = blacklist,
        )

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loading>(awaitItem())
            controlled.emit(TopicPageEmission(cached, provisional = true))
            assertTrue(assertMode<TopicUiState.Mode.Loaded>(awaitItem()).provisional)

            blacklist.block("alice")
            val refiltered = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            assertEquals(setOf(100), refiltered.hiddenNumreponses)
            assertTrue("the local re-filter must NOT fake-settle the page", refiltered.provisional)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `#877 a stale form fetch is dropped after a newer owner took the same page`() = runTest {
        // Gate finding : `request == fetchedFor` cannot tell two successive owners of the SAME page
        // apart — the generation token must drop the late form-fetch reply, like a stale transsearch.
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val gate = CompletableDeferred<Unit>()
        var gated = true
        val repo = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(1, 3, title = "no-form")) }),
            // Consumed in COMPLETION order : the (ungated) pull-to-refresh takes the first item,
            // the released form fetch takes the second.
            refreshTopicsToReturn = listOf(
                fakeTopic(1, 3, title = "refreshed"),
                fakeTopic(1, 3, title = "late-form", searchForm = form),
            ),
        )
        repo.refreshHook = { _, _, _ ->
            if (gated) {
                gated = false
                gate.await()
            }
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repo,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.send(TopicIntent.OpenSearch) // ensureSearchForm suspends on the gate
        viewModel.send(TopicIntent.Refresh) // same page, NEW owner → bumps the generation
        assertEquals("refreshed", assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title)

        gate.complete(Unit) // the late form fetch lands — and must be dropped

        assertEquals(
            "the stale form fetch must not clobber the newer owner",
            "refreshed",
            assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title,
        )
    }

    @Test
    fun `flow failing without prior emission exposes error mode`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { throw IOException("network") }),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        val mode = assertMode<TopicUiState.Mode.Error>(viewModel.state.value)
        assertEquals("network", mode.message)
    }

    @Test
    fun `flow failing with an HFR 5xx exposes the ServerDown error kind`() = runTest {
        // #324 — an HFR outage (HfrServerException 5xx) must be distinguishable from a
        // local network cut by the screen, via the type-derived kind on the Error mode.
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { throw HfrServerException(code = 500, url = "https://forum.hardware.fr/forum2.php") },
            ),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        val mode = assertMode<TopicUiState.Mode.Error>(viewModel.state.value)
        assertEquals(HfrErrorKind.ServerDown, mode.kind)
    }

    @Test
    fun `flow failing with a transport IOException exposes the Network error kind`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { throw UnknownHostException("forum.hardware.fr") }),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        val mode = assertMode<TopicUiState.Mode.Error>(viewModel.state.value)
        assertEquals(HfrErrorKind.Network, mode.kind)
    }

    @Test
    fun `flow failing after a cached emission keeps the cached Loaded state`() = runTest {
        val cached = fakeTopic(page = 1, totalPages = 3, title = "cached")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow {
                    emit(cached)
                    throw IOException("refresh failed")
                },
            ),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        val mode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals(cached, mode.topic)
    }

    @Test
    fun `scrollTo emits a single ScrollToPost effect when the target post is in the loaded page`() = runTest {
        val target = 12_345
        val topic = fakeTopic(
            page = 3,
            totalPages = 5,
            posts = listOf(fakePost(numreponse = target)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 3, scrollTo = target),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            val effect = awaitItem() as TopicEffect.ScrollToPost
            assertEquals(target, effect.numreponse)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scrollTo does not emit an effect when the target post is missing from the page`() = runTest {
        val topic = fakeTopic(page = 1, totalPages = 1, posts = listOf(fakePost(numreponse = 555)))
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 999),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scrollTo emits at most once even when the page reloads`() = runTest {
        val target = 7_777
        val topic = fakeTopic(page = 2, totalPages = 5, posts = listOf(fakePost(numreponse = target)))
        // Two emissions in a row simulate cache + fresh ; the effect must fire only on the
        // first one so the user does not get re-snapped after they have scrolled away.
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow {
                emit(topic)
                emit(topic.copy(title = "fresh"))
            }),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2, scrollTo = target),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            val first = awaitItem() as TopicEffect.ScrollToPost
            assertEquals(target, first.numreponse)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canGoPrevious is false on page 1 and true otherwise`() = runTest {
        val topic = fakeTopic(page = 1, totalPages = 3)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        assertEquals(false, viewModel.state.value.canGoPrevious)
        assertEquals(true, viewModel.state.value.canGoNext)
    }

    @Test
    fun `canGoNext is false on the last page`() = runTest {
        val topic = fakeTopic(page = 5, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))
        val viewModel = topicViewModel(
            request = topicRequest(page = 5),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        assertEquals(true, viewModel.state.value.canGoPrevious)
        assertEquals(false, viewModel.state.value.canGoNext)
    }

    @Test
    fun `retry after error replays the current page and succeeds`() = runTest {
        val topic = fakeTopic(page = 2, totalPages = 4)
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { throw IllegalStateException("boom") },
                flow { emit(topic) },
            ),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        assertMode<TopicUiState.Mode.Error>(viewModel.state.value)

        viewModel.send(TopicIntent.Retry)

        val loadedMode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals(topic, loadedMode.topic)
        assertEquals(
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2), Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.calls,
        )
    }

    @Test
    fun `state isAuthenticated reflects the auth repository (#220)`() = runTest {
        val authed = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        assertEquals(true, authed.state.value.isAuthenticated)

        val anon = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        assertEquals(false, anon.state.value.isAuthenticated)
    }

    @Test
    fun `isAuthenticated flips when the session changes while the topic is open (#220)`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val vm = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = auth,
        )
        assertEquals(true, vm.state.value.isAuthenticated)

        auth.emit(AuthState.Anonymous) // logout while the topic is on screen → gates must close
        assertEquals(false, vm.state.value.isAuthenticated)

        auth.emit(AuthState.Authenticated("xaat")) // log back in → gates reopen
        assertEquals(true, vm.state.value.isAuthenticated)
    }

    @Test
    fun `state carries the connected pseudo for the ownership fallback (#545)`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val vm = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = auth,
        )
        assertEquals("xaat", vm.state.value.connectedPseudo)

        auth.emit(AuthState.Anonymous) // logout → the fallback must stop matching anything
        assertEquals(null, vm.state.value.connectedPseudo)
    }

    @Test
    fun `DeletePost proceeds for an own-by-pseudo post without an edit link (#545)`() = runTest {
        // affichoutils=0 : the toolbar (and its edit link) is absent, so isEditable=false even on
        // the user's own post. Ownership-by-pseudo must let the deletion through.
        val loaded = fakeTopic(
            page = 2,
            totalPages = 3,
            posts = listOf(fakePost(numreponse = 777, isEditable = false, author = "xaat")),
        )
        val refreshed = fakeTopic(page = 2, totalPages = 3, title = "refreshed")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        val deleteRepo = FakeDeletePostRepository(DeletePostResult.Success(deletedWholeTopic = false))
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            deletePostRepository = deleteRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.DeletePost(777))
            assertEquals(TopicEffect.PostDeleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(777, deleteRepo.calls.single().numreponse)
    }

    @Test
    fun `state topBarAutoHide reflects the user preference (build 89)`() = runTest {
        val on = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicTopBarAutoHide = true),
        )
        assertEquals(true, on.state.value.topBarAutoHide)

        val off = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicTopBarAutoHide = false),
        )
        assertEquals(false, off.state.value.topBarAutoHide)
    }

    @Test
    fun `state showPageFabs reflects the user preference (#383)`() = runTest {
        val hidden = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicPageFabs = false),
        )
        assertEquals(false, hidden.state.value.showPageFabs)

        val shown = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicPageFabs = true),
        )
        assertEquals(true, shown.state.value.showPageFabs)
    }

    @Test
    fun `state pollsExpandedDefault reflects the user preference (#456)`() = runTest {
        val collapsed = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicPollsExpanded = false),
        )
        assertEquals(false, collapsed.state.value.pollsExpandedDefault)

        val expanded = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicPollsExpanded = true),
        )
        assertEquals(true, expanded.state.value.pollsExpandedDefault)
    }

    @Test
    fun `state showSignatures reflects the user preference (#330)`() = runTest {
        val hidden = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicSignatures = false),
        )
        assertEquals(false, hidden.state.value.showSignatures)

        val shown = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(topicSignatures = true),
        )
        assertEquals(true, shown.state.value.showSignatures)
    }

    @Test
    fun `state writingSurfacePreset reflects the user preference (#806)`() = runTest {
        val fullEditor = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(
                writingSurfacePreset = WritingSurfacePreset.FULL_EDITOR,
            ),
        )
        assertEquals(WritingSurfacePreset.FULL_EDITOR, fullEditor.state.value.writingSurfacePreset)

        val default = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            userPreferencesRepository = FakeUserPreferencesRepository(),
        )
        assertEquals(WritingSurfacePreset.SHEET, default.state.value.writingSurfacePreset)
    }

    @Test
    fun `DeletePost success emits PostDeleted and force-refreshes the current page (#292)`() = runTest {
        // Page 2 so the editable post 777 is NOT the first post (the FP lives on page 1 and is
        // excluded from deletion). Editable + authenticated + canReply → the VM gate lets it through.
        val loaded = fakeTopic(
            page = 2,
            totalPages = 3,
            posts = listOf(fakePost(numreponse = 777, isEditable = true)),
        )
        val refreshed = fakeTopic(page = 2, totalPages = 3, title = "refreshed")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        val deleteRepo = FakeDeletePostRepository(DeletePostResult.Success(deletedWholeTopic = false))
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            deletePostRepository = deleteRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.DeletePost(777))
            assertEquals(TopicEffect.PostDeleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, deleteRepo.calls.size)
        assertEquals(777, deleteRepo.calls.single().numreponse)
        assertEquals(SAMPLE_SUBCAT, deleteRepo.calls.single().subcat)
        assertEquals(
            "a successful delete force-refreshes the current page so the post disappears",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.refreshCalls,
        )
    }

    @Test
    fun `DeletePost failure emits PostDeleteFailed with the mapped reason and does not refresh (#292)`() =
        runTest {
            val loaded = fakeTopic(
                page = 2,
                totalPages = 3,
                posts = listOf(fakePost(numreponse = 777, isEditable = true)),
            )
            val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(loaded) }))
            val deleteRepo = FakeDeletePostRepository(
                DeletePostResult.Failure(ReplyFailureReason.TopicLocked),
            )
            val viewModel = topicViewModel(
                request = topicRequest(page = 2),
                topicRepository = repository,
                authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
                deletePostRepository = deleteRepo,
            )

            viewModel.effects.test {
                viewModel.send(TopicIntent.DeletePost(777))
                val effect = awaitItem() as TopicEffect.PostDeleteFailed
                assertEquals(DeleteFailureReason.TopicLocked, effect.reason)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue("a failed delete must not force-refresh", repository.refreshCalls.isEmpty())
        }

    @Test
    fun `DeletePost refuses the first post and never POSTs (#292)`() = runTest {
        // Page 1, single post → it IS the first post. Even editable, the VM must refuse (deleting the
        // FP removes the whole topic, out of scope) and never reach the repository.
        val loaded = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(numreponse = 100, isEditable = true)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(loaded) }))
        val deleteRepo = FakeDeletePostRepository()
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            deletePostRepository = deleteRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.DeletePost(100))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("the first post must never be deleted", deleteRepo.calls.isEmpty())
    }

    @Test
    fun `DeletePost refuses a non-editable post and never POSTs (#292)`() = runTest {
        val loaded = fakeTopic(
            page = 2,
            totalPages = 3,
            posts = listOf(fakePost(numreponse = 777, isEditable = false)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(loaded) }))
        val deleteRepo = FakeDeletePostRepository()
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            deletePostRepository = deleteRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.DeletePost(777))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("a non-editable post must never be deleted", deleteRepo.calls.isEmpty())
    }

    @Test
    fun `DeletePost refuses when the session is not authenticated and never POSTs (#292)`() = runTest {
        val loaded = fakeTopic(
            page = 2,
            totalPages = 3,
            posts = listOf(fakePost(numreponse = 777, isEditable = true)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(loaded) }))
        val deleteRepo = FakeDeletePostRepository()
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            deletePostRepository = deleteRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.DeletePost(777))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("a logged-out session must never delete", deleteRepo.calls.isEmpty())
    }

    @Test
    fun `DeletePost proceeds for a subcat-0 topic (cat without sub-category) (#292)`() = runTest {
        // #292 Codex review: subcat=0 is a valid HFR value (cat without sub-category), so the VM must
        // NOT block it — only the SUBCAT_UNKNOWN sentinel (-1) is rejected.
        val loaded = fakeTopic(
            page = 2,
            totalPages = 3,
            posts = listOf(fakePost(numreponse = 777, isEditable = true)),
            subcat = 0,
        )
        val refreshed = fakeTopic(page = 2, totalPages = 3, subcat = 0, title = "refreshed")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        val deleteRepo = FakeDeletePostRepository(DeletePostResult.Success(deletedWholeTopic = false))
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            deletePostRepository = deleteRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.DeletePost(777))
            assertEquals(TopicEffect.PostDeleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("subcat=0 must reach the repository", 1, deleteRepo.calls.size)
        assertEquals(0, deleteRepo.calls.single().subcat)
    }

    @Test
    fun `forceRefresh from the request is forwarded to observeTopicPage (#231)`() = runTest {
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) }))
        topicViewModel(
            request = topicRequest(page = 1).copy(forceRefresh = true),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        assertEquals(
            "a flag-open request must force the topic cache to refresh",
            true,
            repository.lastForceRefresh,
        )
    }

    // Construction seam: the ViewModel now also takes a UserPreferencesRepository (for the build 89
    // top-bar auto-hide preference) which none of these tests exercise, so it defaults to a no-op
    // fake. Keeps every existing call site unchanged bar the constructor → helper rename.
    @Test
    fun `blacklisted authors posts are reported hidden by numreponse, others are not`() = runTest {
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(
                fakePost(100, author = "Alice"),
                fakePost(101, author = "Bob"),
                // canonical match is case/whitespace-insensitive: "alice" matches the "Alice" rule.
                fakePost(102, author = "  alice  "),
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = FakeBlacklistRepository(blockedCanonicals = setOf("alice")),
        )

        viewModel.state.test {
            val mode = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            assertEquals(setOf(100, 102), mode.hiddenNumreponses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty blacklist hides nothing`() = runTest {
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.state.test {
            val mode = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            assertEquals(emptySet<Int>(), mode.hiddenNumreponses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blocking an author after load hides their posts live`() = runTest {
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val blacklist = FakeBlacklistRepository()
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = blacklist,
        )

        viewModel.state.test {
            assertEquals(emptySet<Int>(), assertMode<TopicUiState.Mode.Loaded>(awaitItem()).hiddenNumreponses)
            blacklist.block("alice")
            assertEquals(setOf(100), assertMode<TopicUiState.Mode.Loaded>(awaitItem()).hiddenNumreponses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SetAuthorBlocked intent blocks then unblocks the author live`() = runTest {
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = FakeBlacklistRepository(),
        )

        viewModel.state.test {
            assertEquals(emptySet<Int>(), assertMode<TopicUiState.Mode.Loaded>(awaitItem()).hiddenNumreponses)
            viewModel.send(TopicIntent.SetAuthorBlocked("Alice", blocked = true))
            assertEquals(setOf(100), assertMode<TopicUiState.Mode.Loaded>(awaitItem()).hiddenNumreponses)
            viewModel.send(TopicIntent.SetAuthorBlocked("Alice", blocked = false))
            assertEquals(emptySet<Int>(), assertMode<TopicUiState.Mode.Loaded>(awaitItem()).hiddenNumreponses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the canonical blocked set is exposed for quote masking and re-filters live (#785)`() = runTest {
        // #785 — the screen provides Loaded.blockedQuoteAuthors to the quote renderer
        // (LocalBlockedQuoteAuthors), so the set must (a) land with the initial load — even when the
        // blocked author has NO post on the page, only citations of them — and (b) follow live
        // blacklist changes through the same seam as hiddenNumreponses (loadedMode).
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val blacklist = FakeBlacklistRepository(blockedCanonicals = setOf("charlie"))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = blacklist,
        )

        viewModel.state.test {
            val initial = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            // charlie posts nothing on this page (hiddenNumreponses empty), yet the canonical set is
            // exposed so a CITATION of charlie in Alice/Bob's posts can be masked by the renderer.
            assertEquals(setOf("charlie"), initial.blockedQuoteAuthors)
            assertEquals(emptySet<Int>(), initial.hiddenNumreponses)

            blacklist.block("Alice")
            val refiltered = assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            assertEquals(setOf("charlie", "alice"), refiltered.blockedQuoteAuthors)
            assertEquals(setOf(100), refiltered.hiddenNumreponses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blocking an author after a pull-to-refresh hides their posts live (#509 beta)`() = runTest {
        // Beta regression: the blacklist used to be collected only inside loadCurrentPage's combine, so
        // a refresh (which cancels loadJob and runs a one-shot refetch) FROZE the live re-filter — a
        // block after a pull did nothing until the next page change. The independent init collector now
        // owns the re-filter so it applies on the refreshed page too.
        val loaded = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val refreshed = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "refreshed",
            posts = listOf(fakePost(100, author = "Alice"), fakePost(101, author = "Bob")),
        )
        val blacklist = FakeBlacklistRepository()
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = blacklist,
        )

        viewModel.send(TopicIntent.Refresh)
        assertEquals(
            "the refreshed page is on screen with nothing hidden yet",
            "refreshed",
            assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title,
        )
        assertEquals(emptySet<Int>(), assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).hiddenNumreponses)

        viewModel.send(TopicIntent.SetAuthorBlocked("Alice", blocked = true))

        // Live re-filter on the refreshed page — NOT only after a page change.
        val mode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("refreshed", mode.topic.title)
        assertEquals(setOf(100), mode.hiddenNumreponses)
    }

    @Test
    fun `a post-submit landing hides authors already blacklisted before the force refresh (#509 beta)`() =
        runTest {
            // Beta regression (#509), re-anchored on the engine (#895 PR 2) : the post-submit
            // landing is built by performSubmitRefresh through loadedMode(), which must see the
            // blacklist seeded by the init collector — an already-blocked author is hidden on the
            // freshly force-fetched page too, not only on cache-aside loads.
            val stale = fakeTopic(
                page = 2,
                totalPages = 2,
                posts = listOf(fakePost(1, author = "Bob")),
            )
            val fresh = fakeTopic(
                page = 2,
                totalPages = 2,
                posts = listOf(fakePost(900, author = "Alice"), fakePost(901, author = "Bob")),
            )
            val repository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(stale) }),
                refreshTopicsToReturn = listOf(fresh),
            )
            val viewModel = topicViewModel(
                request = topicRequest(page = 2),
                topicRepository = repository,
                authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
                // Alice is ALREADY blacklisted when the VM is constructed.
                blacklistRepository = FakeBlacklistRepository(blockedCanonicals = setOf("alice")),
            )

            viewModel.applySubmitResult(targetPage = 2, scrollTo = null)

            val mode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
            assertEquals(fresh, mode.topic)
            assertEquals(
                "an already-blacklisted author must be hidden on the force-refresh landing page",
                setOf(900),
                mode.hiddenNumreponses,
            )
            assertEquals(
                "the landing went through the force-refresh path",
                listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
                repository.refreshCalls,
            )
        }

    @Test
    fun `blocking an author while a search result page is shown hides them live (#509 beta)`() = runTest {
        // Beta regression: a transsearch result page is rendered outside loadCurrentPage (launchSearch),
        // so the frozen combine never re-filtered it. The init collector now re-filters whatever page is
        // on screen, including a search result page.
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val resultForm = form.copy(currentNum = 100)
        val resultTopic = fakeTopic(
            page = 1, totalPages = 1, title = "search-result",
            posts = listOf(fakePost(100, author = "Alice"), fakePost(200, author = "Bob")),
            searchForm = resultForm,
        )
        val searchRepo = FakeTopicSearchRepository(result = resultTopic)
        val blacklist = FakeBlacklistRepository()
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = searchableRepo(form),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            blacklistRepository = blacklist,
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(
            "the search result page is on screen",
            "search-result",
            assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title,
        )

        viewModel.send(TopicIntent.SetAuthorBlocked("Alice", blocked = true))

        val mode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("search-result", mode.topic.title)
        assertEquals(setOf(100), mode.hiddenNumreponses)
    }

    // ─── intra-topic search (#546) ───────────────────────────────────────────────

    @Test
    fun `#877 OpenSearch without a form opens the bar and fetches a fresh form`() = runTest {
        // The TTL-skip cache path serves a settled page WITHOUT a searchForm (transient, never
        // cached). Pre-#877 the Loupe simply vanished ; now the bar opens (auth + page on screen)
        // and ensureSearchForm harvests a fresh form in the background.
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val repo = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(1, 3, title = "no-form")) }),
            refreshTopicsToReturn = listOf(fakeTopic(1, 3, title = "fresh-form", searchForm = form)),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repo,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        assertEquals(true, viewModel.state.value.canOpenSearch)
        viewModel.send(TopicIntent.OpenSearch)

        assertEquals(true, viewModel.state.value.search.isActive)
        assertEquals("the fresh-form fetch must fire", 1, repo.refreshCalls.size)
        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("fresh-form", loaded.topic.title)
        assertEquals("the harvested form makes submit usable", true, viewModel.state.value.canSearchInTopic)
    }

    @Test
    fun `#877 OpenSearch stays a no-op when logged out`() = runTest {
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.send(TopicIntent.OpenSearch)

        assertEquals(false, viewModel.state.value.canOpenSearch)
        assertEquals(false, viewModel.state.value.search.isActive)
    }

    @Test
    fun `#877 SubmitSearch without a form fails explicitly and retries the form fetch`() = runTest {
        // Both refresh fetches come back formless (e.g. session lost server-side) : the submit
        // must surface an explicit SearchFailed Toast — never a silent no-op tap — and retry.
        val repo = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(1, 3, title = "no-form")) }),
            refreshTopicsToReturn = listOf(
                fakeTopic(1, 3, title = "still-no-form"),
                fakeTopic(1, 3, title = "still-no-form-2"),
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repo,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        viewModel.send(TopicIntent.OpenSearch) // consumes refresh #1, lands formless
        viewModel.send(TopicIntent.SearchWordChanged("betatest"))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.SearchFailed, awaitItem())
        }
        assertEquals("submit must retry the form fetch", 2, repo.refreshCalls.size)
    }

    @Test
    fun `#894 non-filtered SubmitSearch without a firstnum anchor fails explicitly, never whole-topic`() = runTest {
        // The page on screen is a transsearch RESPONSE : its form has NO firstnum anchor (live
        // contract). A fresh NON-FILTERED submit from there must fail explicitly and refetch the
        // form — silently omitting firstnum would run a whole-topic search the user did not ask for.
        val responseForm = TopicSearchForm(
            hashCheck = "tok",
            topicId = SAMPLE_POST,
            cat = SAMPLE_CAT,
            firstnum = null,
            currentNum = 4242,
        )
        val repo = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(1, 3, searchForm = responseForm)) }),
            refreshTopicsToReturn = listOf(fakeTopic(1, 3, searchForm = responseForm)),
        )
        val searchRepo = FakeTopicSearchRepository(result = fakeTopic(1, 1))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repo,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("betatest"))
        viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.SearchFailed, awaitItem())
        }
        assertEquals("the anchorless submit must never reach the network", 0, searchRepo.requests.size)
    }

    @Test
    fun `SubmitSearch posts the parsed form plus criteria and renders the returned page`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val resultTopic = fakeTopic(page = 1, totalPages = 1, title = "filtered", searchForm = form)
        val searchRepo = FakeTopicSearchRepository(result = resultTopic)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        assertEquals(true, viewModel.state.value.search.isActive)
        viewModel.send(TopicIntent.SearchWordChanged("betatest"))
        viewModel.send(TopicIntent.SearchPseudoChanged("XaTriX"))
        viewModel.send(TopicIntent.SubmitSearch)

        assertEquals(1, searchRepo.requests.size)
        val request = searchRepo.requests.single()
        assertEquals("betatest", request.word)
        assertEquals("XaTriX", request.spseudo)
        assertEquals(true, request.onlyMatches)
        assertEquals(form, request.form)
        assertEquals(null, request.currentNum)
        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("filtered", loaded.topic.title)
        assertEquals(TopicSearchStatus.Done, viewModel.state.value.search.status)
    }

    @Test
    fun `#894 a truncated filtered reply exposes the resume cursor and fetches the next batch`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        // Batch 1 : HFR truncated its scan → the response form advertises the resume cursor.
        // Batch 2 : complete list → no cursor.
        val batch1 = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results-b1",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val batch2 = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results-b2",
            searchForm = form.copy(firstnum = null, currentNum = null),
        )
        val searchRepo = FakeTopicSearchRepository()
        searchRepo.responder = { req -> if (req.currentNum == "500") batch2 else batch1 }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)

        // The truncated reply advertises a further batch — canonical pager intact.
        assertEquals("results-b1", assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title)
        assertEquals(true, viewModel.state.value.search.showingFilteredResults)
        assertEquals(500, viewModel.state.value.search.resumeCursor)
        assertEquals(true, viewModel.state.value.search.hasMoreFilteredResults)
        assertEquals(listOf(1, 2, 3, 4, 5), viewModel.state.value.availablePages)
        // The fresh submit anchored on the current page (web parity, #894).
        assertEquals(999, searchRepo.requests.single().anchor)

        viewModel.send(TopicIntent.SearchNextResultsPage)

        assertEquals(2, searchRepo.requests.size)
        val continuation = searchRepo.requests.last()
        assertEquals("the footer must re-submit the resume cursor", "500", continuation.currentNum)
        assertEquals("a continuation never re-sends an anchor", null, continuation.anchor)
        assertEquals(true, continuation.onlyMatches)
        assertEquals("results-b2", assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title)
        assertEquals("a complete batch ends the continuation", null, viewModel.state.value.search.resumeCursor)
        assertEquals(false, viewModel.state.value.search.hasMoreFilteredResults)
    }

    @Test
    fun `#879 the footer re-submits the FROZEN criteria, not the edited bar (gate finding 1)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val results = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val searchRepo = FakeTopicSearchRepository(result = results)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)

        // The user edits the bar WITHOUT re-submitting, then taps the footer.
        viewModel.send(TopicIntent.SearchWordChanged("autre chose"))
        viewModel.send(TopicIntent.SearchNextResultsPage)

        assertEquals(2, searchRepo.requests.size)
        assertEquals(
            "the next batch must belong to the SUBMITTED search, never the edited bar",
            "iwds",
            searchRepo.requests.last().word,
        )
        assertEquals("500", searchRepo.requests.last().currentNum)
    }

    @Test
    fun `#879 every filtered render repositions at the top of the results (gate finding 2)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val results = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val searchRepo = FakeTopicSearchRepository(result = results)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.ScrollToTopOfResults, awaitItem())
            viewModel.send(TopicIntent.SearchNextResultsPage)
            assertEquals(TopicEffect.ScrollToTopOfResults, awaitItem())
        }
    }

    @Test
    fun `#879 a failed continuation keeps the cursor so the footer stays as retry (gate finding 3)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val batch1 = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results-b1",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val searchRepo = FakeTopicSearchRepository()
        searchRepo.responder = { req ->
            if (req.currentNum != null) throw IOException("boom") else batch1
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(true, viewModel.state.value.search.hasMoreFilteredResults)

        viewModel.send(TopicIntent.SearchNextResultsPage)

        // The fetch failed : the cursor is untouched — the « more » card doubles as retry.
        assertEquals(500, viewModel.state.value.search.resumeCursor)
        assertEquals(true, viewModel.state.value.search.hasMoreFilteredResults)
        assertEquals(true, viewModel.state.value.search.showingFilteredResults)
    }

    @Test
    fun `#894 anti-loop — a continuation cursor that did not advance ends the results`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        // HFR mis-reports : the continuation re-advertises the SAME cursor it was sent — following
        // it would re-serve the same batch forever.
        val batch = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results-b1",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val searchRepo = FakeTopicSearchRepository(result = batch)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(true, viewModel.state.value.search.hasMoreFilteredResults)

        viewModel.send(TopicIntent.SearchNextResultsPage)

        // A non-advancing cursor is treated as the end : footer gone, no infinite loop.
        assertEquals(null, viewModel.state.value.search.resumeCursor)
        assertEquals(false, viewModel.state.value.search.hasMoreFilteredResults)
        assertEquals(true, viewModel.state.value.search.showingFilteredResults)
    }

    @Test
    fun `#894 an EMPTY filtered continuation is the end of results, never « Aucun résultat »`() = runTest {
        // Cadrage F6 — the matches behind the advertised cursor were deleted meanwhile : HFR
        // answers its « aucune réponse n'a été trouvée » page. The displayed batch must STAY on
        // screen, the status settle on Done (not NoResults) and the cursor drop so the footer
        // becomes the end card.
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val batch1 = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results-b1",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val searchRepo = FakeTopicSearchRepository()
        searchRepo.responder = { req ->
            if (req.currentNum != null) throw NoTopicSearchResultsException() else batch1
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(true, viewModel.state.value.search.hasMoreFilteredResults)

        viewModel.send(TopicIntent.SearchNextResultsPage)

        assertEquals(TopicSearchStatus.Done, viewModel.state.value.search.status)
        assertEquals(null, viewModel.state.value.search.resumeCursor)
        assertEquals(false, viewModel.state.value.search.hasMoreFilteredResults)
        assertEquals(true, viewModel.state.value.search.showingFilteredResults)
        assertEquals(
            "the displayed batch must stay on screen",
            "results-b1",
            assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title,
        )
    }

    @Test
    fun `#879 a normal load resets the filtered results state`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val results = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results",
            searchForm = form.copy(firstnum = null, currentNum = 500),
        )
        val searchRepo = FakeTopicSearchRepository(result = results)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(1, 5, searchForm = form)) },
                    flow { emit(fakeTopic(1, 5, title = "normal-again", searchForm = form)) },
                ),
                refreshTopicsToReturn = listOf(fakeTopic(1, 5, title = "refreshed", searchForm = form)),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(true, viewModel.state.value.search.showingFilteredResults)

        viewModel.send(TopicIntent.Refresh)

        // A normal-load owner reset the results state : footer gone, no stale « résultats suivants ».
        assertEquals(false, viewModel.state.value.search.showingFilteredResults)
        assertEquals(null, viewModel.state.value.search.resumeCursor)
        assertEquals(false, viewModel.state.value.search.hasMoreFilteredResults)
    }

    @Test
    fun `#894 from-start submits an explicit 0 anchor, default anchors the current page`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 999)
        val results = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results",
            searchForm = form.copy(firstnum = null, currentNum = null),
        )
        val searchRepo = FakeTopicSearchRepository(result = results)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SearchFromStartChanged(true))
        viewModel.send(TopicIntent.SubmitSearch)

        assertEquals("« depuis le début » = explicit 0", 0, searchRepo.requests.last().anchor)

        viewModel.send(TopicIntent.SearchFromStartChanged(false))
        viewModel.send(TopicIntent.SubmitSearch)

        assertEquals("default anchors the current page", 999, searchRepo.requests.last().anchor)
    }

    @Test
    fun `#894 a fresh submit from a results page reuses the frozen session anchor`() = runTest {
        // The on-screen page is a transsearch RESPONSE (form without anchor) : a new fresh submit
        // must reuse the session anchor captured from the last REAL topic page, never fail and
        // never silently search the whole topic.
        val pageForm = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 777)
        val results = fakeTopic(
            page = 1,
            totalPages = 1,
            title = "results",
            searchForm = pageForm.copy(firstnum = null, currentNum = null),
        )
        val searchRepo = FakeTopicSearchRepository(result = results)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = pageForm)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("iwds"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(777, searchRepo.requests.last().anchor)

        // Second fresh submit FROM the rendered results page (its form has no firstnum).
        viewModel.send(TopicIntent.SearchWordChanged("autre"))
        viewModel.send(TopicIntent.SubmitSearch)

        assertEquals(2, searchRepo.requests.size)
        assertEquals("the session anchor survives the results render", 777, searchRepo.requests.last().anchor)
    }

    @Test
    fun `SubmitSearch does not POST when neither term nor author is set`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val searchRepo = FakeTopicSearchRepository(result = fakeTopic(1, 1))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 1, searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SubmitSearch)

        assertEquals(0, searchRepo.requests.size)
    }

    @Test
    fun `SubmitSearch failure keeps the current page and emits SearchFailed`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val searchRepo = FakeTopicSearchRepository(error = IllegalStateException("boom"))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                // A single flow: the failure path never switches to Mode.Loading and never reloads the
                // page — the page on screen is kept as-is, so observeTopicPage is collected only once.
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 3, title = "loaded", searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.OpenSearch)
            viewModel.send(TopicIntent.SearchWordChanged("x"))
            viewModel.send(TopicIntent.SubmitSearch)

            assertEquals(TopicEffect.SearchFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The page the user was reading stays on screen (never stranded on Loading, never reloaded).
        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("loaded", loaded.topic.title)
        // #546 finding #4 — the search bar reflects the failure and stays open so the user can retry
        // or close it; the status settles on Error rather than back to Idle / Loading.
        assertEquals(TopicSearchStatus.Error, viewModel.state.value.search.status)
        assertTrue(
            "the search bar stays open after a failure so the user can retry",
            viewModel.state.value.search.isActive,
        )
    }

    @Test
    fun `a stale SubmitSearch reply never clobbers a more recent normal page (#546)`() = runTest {
        // #546 finding #1 (latest-wins) — a search POST is on the wire when the user pulls to refresh.
        // The refresh (a normal-load path) bumps the search generation, so when the slow transsearch
        // reply finally lands it must be DROPPED, never overwrite the freshly-refreshed page.
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val loaded = fakeTopic(page = 1, totalPages = 3, title = "loaded", searchForm = form)
        val refreshed = fakeTopic(page = 1, totalPages = 3, title = "refreshed", searchForm = form)
        // The (now stale) search result, would-be title if the guard were missing.
        val staleSearch = fakeTopic(page = 1, totalPages = 1, title = "stale-search", searchForm = form)
        val searchRepo = FakeTopicSearchRepository(result = staleSearch)
        val gate = CompletableDeferred<Unit>()
        searchRepo.gate = { gate.await() }
        // Non-cooperative wait: cancelling searchJob (the refresh taking over) must NOT cut the await,
        // so the fake truly DELIVERS staleSearch after the refresh. This is what forces the result
        // through the `generation` guard — with a cancellable await the reply would never be produced
        // and the test would stay green even if the guard were deleted.
        searchRepo.ignoreCancellation = true
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SubmitSearch) // suspends in the gate → search in flight
        assertEquals(TopicSearchStatus.Loading, viewModel.state.value.search.status)

        // A normal-load path takes over while the search is still on the wire.
        viewModel.send(TopicIntent.Refresh)
        assertEquals(
            "the refresh wins, not the stale search",
            "refreshed",
            (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.title,
        )
        // Fix 1 — the takeover clears the dangling Loading spinner instead of leaving it stuck (the
        // search reply will be guarded out, so submitSearch never writes its own Done/Error status).
        assertEquals(
            "the dangling search spinner is cleared by the takeover, not left spinning",
            TopicSearchStatus.Idle,
            viewModel.state.value.search.status,
        )

        // Release the slow search reply: it IS produced (non-cancellable await) but the generation
        // moved on, so the guard discards it. Without `generation != searchGeneration` the line below
        // would clobber the refreshed page with "stale-search" and flip the status back to Done.
        gate.complete(Unit)
        val finalMode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("the stale search reply must not clobber the refreshed page", "refreshed", finalMode.topic.title)
        // The stale reply must not rewrite the search status either (it stays at the takeover's Idle,
        // never re-flipped to Done by the dropped reply).
        assertEquals(
            "the dropped stale reply must not rewrite the search status",
            TopicSearchStatus.Idle,
            viewModel.state.value.search.status,
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chantier B (#546) — non-filtered result navigation (next / previous)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `fresh non-filtered search scrolls to the first match and marks Done (#546)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        // The transsearch reply: full page anchored on match 100 (currentNum=100), 100 present.
        val resultForm = form.copy(currentNum = 100)
        val resultTopic = fakeTopic(
            page = 1, totalPages = 1, title = "match-1",
            posts = listOf(fakePost(100), fakePost(200)), searchForm = resultForm,
        )
        val searchRepo = FakeTopicSearchRepository(result = resultTopic)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = searchableRepo(form),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.OpenSearch)
            viewModel.send(TopicIntent.SearchWordChanged("x"))
            viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))
            viewModel.send(TopicIntent.SubmitSearch)

            assertEquals(TopicEffect.ScrollToPost(100), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val request = searchRepo.requests.single()
        assertEquals(false, request.isStep)
        assertEquals(null, request.currentNum)
        assertEquals(TopicSearchStatus.Done, viewModel.state.value.search.status)
        assertEquals(false, viewModel.state.value.search.canGoPreviousResult)
        assertEquals(true, viewModel.state.value.search.canGoNextResult)
    }

    @Test
    fun `NextResult steps the cursor forward without firstnum and scrolls to the new match (#546)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 7)
        val first = fakeTopic(1, 1, posts = listOf(fakePost(100)), searchForm = form.copy(currentNum = 100))
        val second = fakeTopic(1, 1, posts = listOf(fakePost(200)), searchForm = form.copy(currentNum = 200))
        val searchRepo = FakeTopicSearchRepository()
        searchRepo.responder = { req -> if (req.isStep) second else first }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = searchableRepo(form),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.ScrollToPost(100), awaitItem()) // fresh first match
            viewModel.send(TopicIntent.NextResult)
            assertEquals(TopicEffect.ScrollToPost(200), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val step = searchRepo.requests.last()
        assertEquals(true, step.isStep)
        assertEquals("100", step.currentNum)
        assertEquals(true, viewModel.state.value.search.canGoPreviousResult)
        assertEquals(true, viewModel.state.value.search.canGoNextResult)
    }

    @Test
    fun `NextResult past the last match keeps the page and signals the end (#546)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 7)
        val first = fakeTopic(
            1, 1, title = "match-1", posts = listOf(fakePost(100)), searchForm = form.copy(currentNum = 100),
        )
        // The end sentinel: currentNum points at a post NOT present on the returned page.
        val sentinel = fakeTopic(
            1, 1, title = "sentinel", posts = listOf(fakePost(100)), searchForm = form.copy(currentNum = 999),
        )
        val searchRepo = FakeTopicSearchRepository()
        searchRepo.responder = { req -> if (req.isStep) sentinel else first }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = searchableRepo(form),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.ScrollToPost(100), awaitItem()) // fresh first match
            viewModel.send(TopicIntent.NextResult)
            assertEquals(TopicEffect.SearchResultsEnd, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The current match's page stays on screen (the sentinel page was NOT rendered).
        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("match-1", loaded.topic.title)
        assertEquals(false, viewModel.state.value.search.canGoNextResult)
    }

    @Test
    fun `PrevResult replays the cursor history and scrolls back to the earlier match (#546)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 7)
        val first = fakeTopic(1, 1, posts = listOf(fakePost(100)), searchForm = form.copy(currentNum = 100))
        val second = fakeTopic(1, 1, posts = listOf(fakePost(200)), searchForm = form.copy(currentNum = 200))
        val searchRepo = FakeTopicSearchRepository()
        // Fresh and the prev-replay (back to index 0) are fresh requests → first; step → second.
        searchRepo.responder = { req -> if (req.isStep) second else first }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = searchableRepo(form),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.ScrollToPost(100), awaitItem()) // fresh first match (index 0)
            viewModel.send(TopicIntent.NextResult)
            assertEquals(TopicEffect.ScrollToPost(200), awaitItem()) // now on match 200 (index 1)
            viewModel.send(TopicIntent.PrevResult)
            assertEquals(TopicEffect.ScrollToPost(100), awaitItem()) // back to match 100
            cancelAndIgnoreRemainingEvents()
        }
        // Going back to the first match: the replay re-issues a FRESH request (index 0).
        assertEquals(false, searchRepo.requests.last().isStep)
        assertEquals(false, viewModel.state.value.search.canGoPreviousResult)
    }

    @Test
    fun `a fresh search with no match settles on NoResults, not Error (#546)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val searchRepo = FakeTopicSearchRepository(error = NoTopicSearchResultsException())
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(flow { emit(fakeTopic(1, 3, title = "loaded", searchForm = form)) }),
            ),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.OpenSearch)
            viewModel.send(TopicIntent.SearchWordChanged("topic"))
            viewModel.send(TopicIntent.SubmitSearch)
            // No-result is NOT a failure: no SearchFailed effect is emitted.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(TopicSearchStatus.NoResults, viewModel.state.value.search.status)
        // The page the user was reading stays on screen.
        assertEquals("loaded", assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title)
        assertEquals(true, viewModel.state.value.search.isActive)
    }

    @Test
    fun `NextResult after stepping back re-walks history without corrupting it (#546)`() = runTest {
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 7)
        val m100 = fakeTopic(1, 1, posts = listOf(fakePost(100)), searchForm = form.copy(currentNum = 100))
        val m200 = fakeTopic(1, 1, posts = listOf(fakePost(200)), searchForm = form.copy(currentNum = 200))
        val m300 = fakeTopic(1, 1, posts = listOf(fakePost(300)), searchForm = form.copy(currentNum = 300))
        val searchRepo = FakeTopicSearchRepository()
        // Fresh → 100 ; STEP advances by the cursor sent (100→200, 200→300). A « previous » replay is
        // a step from the predecessor, so it also resolves through this map.
        searchRepo.responder = { req ->
            when {
                !req.isStep -> m100
                req.currentNum == "100" -> m200
                req.currentNum == "200" -> m300
                else -> error("unexpected step from ${req.currentNum}")
            }
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = searchableRepo(form),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = searchRepo,
        )

        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SearchOnlyMatchesChanged(false))

        viewModel.effects.test {
            viewModel.send(TopicIntent.SubmitSearch)
            assertEquals(TopicEffect.ScrollToPost(100), awaitItem())
            viewModel.send(TopicIntent.NextResult)
            assertEquals(TopicEffect.ScrollToPost(200), awaitItem())
            viewModel.send(TopicIntent.NextResult)
            assertEquals(TopicEffect.ScrollToPost(300), awaitItem())
            // Step back to 200, then forward again: history must stay [100,200,300], so the next
            // forward lands on 300 and a further « previous » walks back to 200 — NOT a corrupted
            // [100,200,300,200] that would send « previous » back to 300 (Codex review).
            viewModel.send(TopicIntent.PrevResult)
            assertEquals(TopicEffect.ScrollToPost(200), awaitItem())
            viewModel.send(TopicIntent.NextResult)
            assertEquals(TopicEffect.ScrollToPost(300), awaitItem())
            viewModel.send(TopicIntent.PrevResult)
            assertEquals(TopicEffect.ScrollToPost(200), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Chantier B (#546) — a topic repo that loads a 5-page topic carrying [form] (keeps lines short). */
    // ──────────────────────────────────────────────────────────────────────
    // #809 — long-press flag removal from the topic top bar
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `requestRemoveTopicFlag resolves then moves to Confirming when a flag is found (#809)`() = runTest {
        val flag = fakeFlag()
        // Gate the lookup so the Resolving frame is observable (an instant findFlag would be conflated
        // by the StateFlow before the collector reads it).
        val gate = CompletableDeferred<Unit>()
        val flagRepo = FakeFlagRepository(flagToFind = flag).apply { findFlagGate = gate }
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.removeTopicFlagState.test {
            assertEquals(RemoveTopicFlagState.Idle, awaitItem())
            viewModel.send(TopicIntent.RequestRemoveTopicFlag)
            assertEquals(RemoveTopicFlagState.Resolving, awaitItem())
            gate.complete(Unit)
            val confirming = awaitItem() as RemoveTopicFlagState.Confirming
            assertEquals(flag, confirming.flag)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, flagRepo.findFlagCalls)
    }

    @Test
    fun `requestRemoveTopicFlag emits NotFound and returns to Idle when no flag is found (#809)`() = runTest {
        val flagRepo = FakeFlagRepository(flagToFind = null)
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag)

        viewModel.effects.test {
            assertEquals(TopicEffect.TopicFlagNotFound, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(RemoveTopicFlagState.Idle, viewModel.removeTopicFlagState.value)
        assertTrue(
            "a NotFound resolution must never reach the confirmation dialog",
            viewModel.removeTopicFlagState.value !is RemoveTopicFlagState.Confirming,
        )
    }

    @Test
    fun `requestRemoveTopicFlag folds a resolve failure into NotFound and returns to Idle (#809)`() = runTest {
        // Gate Codex #809 — a findFlag that dies mid-resolve (cancelled in-flight fetch after an
        // account switch, runtime failure) must not wedge the state in Resolving with no event :
        // it folds to NotFound and the long-press stays usable.
        val flagRepo = FakeFlagRepository(flagToFind = fakeFlag()).apply {
            findFlagError = RuntimeException("resolve died")
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag)

        viewModel.effects.test {
            assertEquals(TopicEffect.TopicFlagNotFound, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(RemoveTopicFlagState.Idle, viewModel.removeTopicFlagState.value)

        // The guard is released : a next long-press resolves again instead of being wedged.
        flagRepo.findFlagError = null
        viewModel.send(TopicIntent.RequestRemoveTopicFlag)
        assertTrue(
            "after a failed resolve, a retry must reach Confirming",
            viewModel.removeTopicFlagState.value is RemoveTopicFlagState.Confirming,
        )
    }

    @Test
    fun `requestRemoveTopicFlag ignores a second press while Resolving (#809)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val flagRepo = FakeFlagRepository(flagToFind = fakeFlag()).apply { findFlagGate = gate }
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag) // → Resolving, awaiting the gate
        viewModel.send(TopicIntent.RequestRemoveTopicFlag) // ignored while Resolving

        assertEquals(RemoveTopicFlagState.Resolving, viewModel.removeTopicFlagState.value)
        assertEquals("the second long-press must not launch a duplicate lookup", 1, flagRepo.findFlagCalls)
        gate.complete(Unit)
    }

    @Test
    fun `requestRemoveTopicFlag ignores a press while a removal is in flight (#809)`() = runTest {
        val removeGate = CompletableDeferred<Unit>()
        val flagRepo = FakeFlagRepository(flagToFind = fakeFlag()).apply { removeFlagGate = removeGate }
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag) // resolves instantly → Confirming
        viewModel.confirmRemoveTopicFlag() // → Removing, awaiting the removal gate
        assertTrue(viewModel.removeTopicFlagState.value is RemoveTopicFlagState.Removing)

        viewModel.send(TopicIntent.RequestRemoveTopicFlag) // ignored while Removing

        assertEquals("no re-resolution while a removal is in flight", 1, flagRepo.findFlagCalls)
        assertTrue(viewModel.removeTopicFlagState.value is RemoveTopicFlagState.Removing)
        removeGate.complete(Unit)
    }

    @Test
    fun `confirmRemoveTopicFlag success emits Success and resets to Idle (#809)`() = runTest {
        val flag = fakeFlag(title = "Redface 2")
        val flagRepo = FakeFlagRepository(flagToFind = flag, removeResult = Result.success(Unit))
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag)
        assertTrue(viewModel.removeTopicFlagState.value is RemoveTopicFlagState.Confirming)
        viewModel.confirmRemoveTopicFlag()

        viewModel.effects.test {
            assertEquals(TopicEffect.TopicFlagRemoved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(RemoveTopicFlagState.Idle, viewModel.removeTopicFlagState.value)
        assertEquals(1, flagRepo.removeFlagCalls)
        assertEquals(flag, flagRepo.lastRemovedFlag)
    }

    @Test
    fun `confirmRemoveTopicFlag failure emits Failure and resets to Idle (#809)`() = runTest {
        val flag = fakeFlag(title = "Redface 2")
        val flagRepo = FakeFlagRepository(
            flagToFind = flag,
            removeResult = Result.failure(IllegalStateException("delflag refused")),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag)
        viewModel.confirmRemoveTopicFlag()

        viewModel.effects.test {
            assertEquals(TopicEffect.TopicFlagRemovalFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The Removing lock is always released in the finally block (#603 fork 5 lesson).
        assertEquals(RemoveTopicFlagState.Idle, viewModel.removeTopicFlagState.value)
    }

    @Test
    fun `confirmRemoveTopicFlag folds a raw removeFlag throw into RemovalFailed (#809)`() = runTest {
        // Review #809 — removeFlag CAN throw outside its Result (evictFlagFromCaches runs in
        // `.onSuccess`, past the internal runCatching) : the user still gets the failure toast
        // and the Removing lock is released, instead of an app crash with no feedback.
        val flagRepo = FakeFlagRepository(flagToFind = fakeFlag()).apply {
            removeFlagError = IllegalStateException("evict blew up")
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag)
        viewModel.confirmRemoveTopicFlag()

        viewModel.effects.test {
            assertEquals(TopicEffect.TopicFlagRemovalFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(RemoveTopicFlagState.Idle, viewModel.removeTopicFlagState.value)
    }

    @Test
    fun `cancelRemoveTopicFlag dismisses the confirmation without removing (#809)`() = runTest {
        val flagRepo = FakeFlagRepository(flagToFind = fakeFlag())
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 3)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            flagRepository = flagRepo,
        )

        viewModel.send(TopicIntent.RequestRemoveTopicFlag)
        assertTrue(viewModel.removeTopicFlagState.value is RemoveTopicFlagState.Confirming)
        viewModel.cancelRemoveTopicFlag()

        assertEquals(RemoveTopicFlagState.Idle, viewModel.removeTopicFlagState.value)
        assertEquals("cancelling must never call delflag", 0, flagRepo.removeFlagCalls)
    }

    // ──────────────────────────────────────────────────────────────────────
    // #895 étape 4 — in-ViewModel page engine (switch / LRU / anchors / landing
    // priorities / submit result / consumable entry intentions). Unbranched in
    // production until the navigation switch-over PR : these tests ARE its harness.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `switchToPage without a snapshot holds the departed page then loads the target (#895 slash #910)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5)) },
                flow { emit(fakeTopic(3, 5)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            viewModel.effects.test {
                viewModel.switchToPage(3)
                assertEquals(TopicEffect.ScrollToTop(3), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            val emitted = cancelAndConsumeRemainingEvents()
                .mapNotNull { (it as? app.cash.turbine.Event.Item)?.value }
            assertTrue(
                "an unvisited page bridges with the departed page held provisional (#910) — " +
                    "a snapshot hit would swap Loaded(non-provisional) directly",
                emitted.any { (it.mode as? TopicUiState.Mode.Loaded)?.provisional == true },
            )
        }
        assertEquals(3, viewModel.state.value.request.page)
        assertEquals(3, (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.page)
        assertEquals(
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2), Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.calls,
        )
    }

    @Test
    fun `a revisit activates the LRU snapshot with no Loading and lands on the saved anchor (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5, title = "first-visit")) },
                flow { emit(fakeTopic(3, 5)) },
                // The revisit's in-place refresh settles with fresher content.
                flow { emit(fakeTopic(2, 5, title = "refreshed")) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        val departure = TopicScrollAnchor(index = 12, offset = 34)

        viewModel.effects.test {
            viewModel.switchToPage(3, departureAnchor = departure)
            assertEquals(TopicEffect.ScrollToTop(3), awaitItem())
            viewModel.state.test {
                assertMode<TopicUiState.Mode.Loaded>(awaitItem())
                viewModel.switchToPage(2)
                val emitted = cancelAndConsumeRemainingEvents()
                    .mapNotNull { (it as? app.cash.turbine.Event.Item)?.value }
                assertTrue(
                    "the revisit must never pass through Loading",
                    emitted.none { it.mode is TopicUiState.Mode.Loading },
                )
            }
            assertEquals(TopicEffect.ScrollToAnchor(departure, 2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The snapshot bridged the switch, then the cache-aside refresh settled in place.
        assertEquals("refreshed", (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.title)
        assertEquals(2, viewModel.state.value.request.page)
    }

    @Test
    fun `the snapshot map is an LRU bounded to 5 terminal pages (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = buildList {
                add(flow { emit(fakeTopic(1, 9)) })
                (2..6).forEach { page -> add(flow { emit(fakeTopic(page, 9)) }) }
                // Page 1 was evicted (6 terminal pages > 5) : the return re-loads it.
                add(flow { emit(fakeTopic(1, 9)) })
            },
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        (2..6).forEach { viewModel.switchToPage(it) }

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            viewModel.switchToPage(1)
            val emitted = cancelAndConsumeRemainingEvents()
                .mapNotNull { (it as? app.cash.turbine.Event.Item)?.value }
            assertTrue(
                "page 1 was evicted from the LRU : the return is a COLD switch (provisional hold, " +
                    "#910) — a surviving snapshot would swap Loaded(non-provisional) atomically",
                emitted.any { (it.mode as? TopicUiState.Mode.Loaded)?.provisional == true },
            )
        }
        assertEquals(7, repository.calls.size)
    }

    @Test
    fun `a rapid A to B to C switch applies nothing from the abandoned target (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5)) },
                // Page 3's load hangs — the user switches again before it lands.
                flow { kotlinx.coroutines.awaitCancellation() },
                flow { emit(fakeTopic(4, 5)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.switchToPage(3)
        viewModel.switchToPage(4)

        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("the abandoned page 3 must never land", 4, loaded.topic.page)
        assertEquals(4, viewModel.state.value.request.page)
    }

    @Test
    fun `a blacklist change while a page is memorized re-filters at reactivation (#895)`() = runTest {
        val blockedPost = fakePost(numreponse = 501, author = "troll")
        val blacklist = FakeBlacklistRepository()
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5, posts = listOf(blockedPost, fakePost(502)))) },
                flow { emit(fakeTopic(3, 5)) },
                flow { kotlinx.coroutines.awaitCancellation() },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            blacklistRepository = blacklist,
        )
        viewModel.switchToPage(3)
        // Blocked while page 2 lives only as a memory snapshot.
        blacklist.block("troll")

        viewModel.switchToPage(2)

        // The snapshot activation (the refresh is held in flight) already hides the blocked post :
        // the raw Topic was stored, the filter is recomputed through loadedMode (cadrage F2).
        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals(2, loaded.topic.page)
        assertEquals(setOf(501), loaded.hiddenNumreponses)
    }

    @Test
    fun `applySubmitResult falls back on the canonical page and lands at the bottom (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 3)) },
                flow { emit(fakeTopic(3, 3)) },
            ),
            // The canonical page IS the tail (a plain reply lands there) : no #226 redirect.
            refreshTopicsToReturn = listOf(fakeTopic(3, 3, title = "fresh")),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        viewModel.effects.test {
            // The user switched pages after the route was created : the canonical page is 3, not 2.
            viewModel.switchToPage(3)
            assertEquals(TopicEffect.ScrollToTop(3), awaitItem())
            viewModel.applySubmitResult(targetPage = null, scrollTo = null)
            assertEquals(TopicEffect.ScrollToEndOfPage(3), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "the submit refresh must force-fetch the CANONICAL page (Sol point 1)",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.refreshCalls,
        )
        assertEquals("fresh", (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.title)
    }

    @Test
    fun `applySubmitResult with a scrollTo lands on the freshly-published post (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(2, 5)) }),
            refreshTopicsToReturn = listOf(fakeTopic(2, 5, posts = listOf(fakePost(777)))),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            viewModel.applySubmitResult(targetPage = 2, scrollTo = 777)
            assertEquals(TopicEffect.ScrollToPost(777), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the internal overflow redirect fires once and never chases a moving tail (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(2, 2)) }),
            refreshTopicsToReturn = listOf(
                // The plain reply overflowed : page 2 now reports 3 pages.
                fakeTopic(2, 3),
                // A concurrent poster pushed totalPages further DURING our redirect refresh :
                // the landing is terminal, no second redirect (anti-chase, Sol point 3).
                fakeTopic(3, 4),
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            viewModel.applySubmitResult(targetPage = 2, scrollTo = null)
            assertEquals(TopicEffect.ScrollToEndOfPage(3), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2), Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.refreshCalls,
        )
        assertEquals(3, viewModel.state.value.request.page)
    }

    @Test
    fun `goToPost pushes the jump chain and returnFromJump lands back on the departure anchor (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5)) },
                flow { emit(fakeTopic(4, 5, posts = listOf(fakePost(900)))) },
                flow { emit(fakeTopic(2, 5)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        val departure = TopicScrollAnchor(index = 3, offset = 10)

        viewModel.effects.test {
            viewModel.goToPost(targetPage = 4, numreponse = 900, departureAnchor = departure)
            assertEquals(TopicEffect.ScrollToPost(900), awaitItem())
            assertTrue(viewModel.returnFromJump())
            assertEquals(TopicEffect.ScrollToAnchor(departure, 2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, viewModel.state.value.request.page)
        assertFalse("the chain is unwound : back must leave the topic", viewModel.returnFromJump())
    }

    @Test
    fun `a manual page switch clears the jump chain (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5)) },
                flow { emit(fakeTopic(4, 5, posts = listOf(fakePost(900)))) },
                flow { emit(fakeTopic(5, 5)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.goToPost(targetPage = 4, numreponse = 900)
        viewModel.switchToPage(5)

        assertFalse(
            "a manual navigation invalidates the quote-jump returns (browser-like, #782)",
            viewModel.returnFromJump(),
        )
    }

    @Test
    fun `a strict page-minus-one switch lands at the bottom (#412 via engine)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(3, 5)) },
                flow { emit(fakeTopic(2, 5)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 3),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.effects.test {
            viewModel.switchToPage(2)
            assertEquals(TopicEffect.ScrollToEndOfPage(2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prefetch re-arms on the switched page (#895)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 6)) },
                flow { emit(fakeTopic(4, 6)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        viewModel.switchToPage(4)

        assertEquals(
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3), Triple(SAMPLE_CAT, SAMPLE_POST, 5)),
            repository.prefetches,
        )
    }

    @Test
    fun `process restore adopts the switched page and its anchor over the route (#895)`() = runTest {
        val handle = SavedStateHandle()
        val first = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(2, 5)) },
                    flow { emit(fakeTopic(3, 5)) },
                ),
            ),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        first.switchToPage(3)
        first.reportPageAnchor(TopicScrollAnchor(index = 7, offset = 42))

        // Process death : a NEW ViewModel from the SAME route + the SAME handle.
        val restoredRepo = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(3, 5)) }))
        val restored = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = restoredRepo,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )

        assertEquals(
            "SavedState wins over the route's initial page (Sol F1)",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            restoredRepo.calls,
        )
        assertEquals(3, restored.state.value.request.page)
        restored.effects.test {
            assertEquals(TopicEffect.ScrollToAnchor(TopicScrollAnchor(7, 42), 3), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an initial scrollTo is consumed once and never replays after process death (#895)`() = runTest {
        val handle = SavedStateHandle()
        val page = fakeTopic(2, 5, posts = listOf(fakePost(55)))
        val first = topicViewModel(
            request = topicRequest(page = 2, scrollTo = 55),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(page) })),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        first.effects.test {
            assertEquals(TopicEffect.ScrollToPost(55), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val restored = topicViewModel(
            request = topicRequest(page = 2, scrollTo = 55),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(page) })),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        restored.effects.test {
            expectNoEvents()
            cancel()
        }
    }

    @Test
    fun `a consumed forceRefresh does not replay after process death (#231 via engine)`() = runTest {
        val handle = SavedStateHandle()
        val firstRepo = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 5)) }))
        topicViewModel(
            request = TopicRequest(
                cat = SAMPLE_CAT,
                post = SAMPLE_POST,
                page = 2,
                scrollTo = null,
                forceRefresh = true,
            ),
            topicRepository = firstRepo,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        assertEquals(true, firstRepo.lastForceRefresh)

        val restoredRepo = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 5)) }))
        topicViewModel(
            request = TopicRequest(
                cat = SAMPLE_CAT,
                post = SAMPLE_POST,
                page = 2,
                scrollTo = null,
                forceRefresh = true,
            ),
            topicRepository = restoredRepo,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        assertEquals(
            "the terminal fresh emission consumed the #231 catch-up : no TTL bypass on restore",
            false,
            restoredRepo.lastForceRefresh,
        )
    }

    @Test
    fun `a deletion invalidates every memory snapshot (#895)`() = runTest {
        val deletable = fakePost(numreponse = 610, isEditable = true)
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5, posts = listOf(fakePost(600)))) },
                flow { emit(fakeTopic(3, 5, posts = listOf(deletable))) },
                flow { emit(fakeTopic(2, 5, posts = listOf(fakePost(600)))) },
            ),
            refreshTopicsToReturn = listOf(fakeTopic(3, 5)),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        viewModel.switchToPage(3)
        // Page 2 lives as a snapshot ; the deletion (on page 3) may shift the whole pagination.
        viewModel.send(TopicIntent.DeletePost(610))

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            viewModel.switchToPage(2)
            val emitted = cancelAndConsumeRemainingEvents()
                .mapNotNull { (it as? app.cash.turbine.Event.Item)?.value }
            assertTrue(
                "post-delete, the stale page-2 snapshot must not serve : the return is a COLD " +
                    "switch (provisional hold, #910), never an atomic snapshot swap",
                emitted.any { (it.mode as? TopicUiState.Mode.Loaded)?.provisional == true },
            )
        }
    }

    @Test
    fun `an interrupted page resolution replays after process death (#750 via engine)`() = runTest {
        val handle = SavedStateHandle()
        val hangingSearch = object : SearchRepository {
            override suspend fun search(request: SearchRequest): SearchResultPage =
                error("unused by TopicViewModel")

            override suspend fun resolveSearchResultPage(cat: Int, post: Int, numreponse: Int): Int? =
                kotlinx.coroutines.awaitCancellation()
        }
        topicViewModel(
            request = topicRequest(page = 1, scrollTo = 4242, resolveScrollToPage = true),
            // The probe never resolves (virtual time expires the #750 timeout → degraded
            // fallback on the untrusted page 1) : that fallback load must succeed…
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 40)) })),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            searchRepository = hangingSearch,
            savedStateHandle = handle,
        )

        // …but the UNRESOLVED page is deliberately never persisted as canonical (gate Sol PR1
        // bloquant 1) : after process death the restored ViewModel re-runs the resolution.
        val restoredSearch = FakeSearchRepository(pageToResolve = 37)
        val restoredRepo = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(37, 40)) }))
        val restored = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 4242, resolveScrollToPage = true),
            topicRepository = restoredRepo,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            searchRepository = restoredSearch,
            savedStateHandle = handle,
        )

        assertEquals("the restore must re-probe", 1, restoredSearch.resolveCalls.size)
        assertEquals(37, restored.state.value.request.page)
        assertEquals(listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 37)), restoredRepo.calls)
    }

    @Test
    fun `a superseded landing never fires on the new page (#895 gate)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5)) },
                flow { emit(fakeTopic(3, 5, posts = listOf(fakePost(999)))) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.effects.test {
            // Same-page jump to a post NOT on the page : the Post landing stays pending.
            viewModel.goToPost(targetPage = 2, numreponse = 999)
            expectNoEvents()
            // Manual switch to page 3 — which happens to CONTAIN numreponse 999. The stale
            // Post landing is superseded by the switch's own landing and must never fire.
            viewModel.switchToPage(3)
            assertEquals(TopicEffect.ScrollToTop(3), awaitItem())
            expectNoEvents()
            cancel()
        }
    }

    @Test
    fun `a late manual-refresh reply never overwrites a newer page (#895 gate r2)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5, title = "page-2")) },
                flow { emit(fakeTopic(3, 5, title = "page-3")) },
            ),
            refreshTopicsToReturn = listOf(fakeTopic(2, 5, title = "late-refresh")),
        )
        // NonCancellable : the switch cancels the refresh job, but the reply still LANDS —
        // the only setup that actually exercises the generation belt (cf. the transsearch twin).
        repository.refreshHook = { _, _, _ -> withContext(NonCancellable) { gate.await() } }
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.send(TopicIntent.Refresh)
        viewModel.switchToPage(3)
        gate.complete(Unit)

        val loaded = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("the stale refresh reply must be dropped", "page-3", loaded.topic.title)
    }

    @Test
    fun `a search takeover reclaims an in-flight refresh spinner (#895 gate r4)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }),
            refreshTopicsToReturn = listOf(fakeTopic(1, 5, searchForm = form)),
        )
        repository.refreshHook = { _, _, _ -> withContext(NonCancellable) { gate.await() } }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = FakeTopicSearchRepository(result = fakeTopic(1, 5, searchForm = form)),
        )

        viewModel.send(TopicIntent.Refresh)
        assertTrue(viewModel.state.value.isRefreshing)
        // launchSearch takes the page over WITHOUT going through becomePageOwner : it must
        // reclaim the spinner itself, or the superseded refresh's generation-guarded finally
        // leaves it stuck forever (and `refresh()` gates on it, blocking every future pull).
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("plop"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertFalse("the search takeover must reclaim the spinner", viewModel.state.value.isRefreshing)

        gate.complete(Unit)
        assertFalse(
            "the superseded refresh must not resurrect or re-clear a newer owner's spinner",
            viewModel.state.value.isRefreshing,
        )
    }

    @Test
    fun `a switch persists the target's known anchor for process restore (#895 gate r2)`() = runTest {
        val handle = SavedStateHandle()
        val anchor = TopicScrollAnchor(index = 5, offset = 17)
        val first = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(2, 5)) },
                    flow { emit(fakeTopic(3, 5)) },
                    flow { emit(fakeTopic(2, 5)) },
                ),
            ),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        first.switchToPage(3, departureAnchor = anchor)
        // The return targets a page whose anchor lives in RAM : it must be persisted AT the
        // switch — a process death before any reportPageAnchor would otherwise lose it.
        first.switchToPage(2)

        val restored = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(2, 5)) })),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            savedStateHandle = handle,
        )
        restored.effects.test {
            assertEquals(TopicEffect.ScrollToAnchor(anchor, 2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // #910 — stale-while-switching (cold-switch grace)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a fast cold switch keeps the departed page then swaps without any skeleton (#910)`() = runTest {
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(2, 5, title = "departed")) },
                    // The target lands fast but not synchronously (50 ms — well within the grace).
                    flow {
                        kotlinx.coroutines.delay(50)
                        emit(fakeTopic(3, 5, title = "target"))
                    },
                ),
            ),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        viewModel.switchToPage(3)
        // Mid-grace : the departed page is held on screen, flagged provisional, canonical = 3.
        val held = viewModel.state.value
        val heldMode = assertMode<TopicUiState.Mode.Loaded>(held)
        assertEquals("the departed page stays on screen during the grace", "departed", heldMode.topic.title)
        assertTrue("the hold is flagged provisional (hairline, honest pill)", heldMode.provisional)
        assertEquals(3, held.request.page)

        // Record every state from here : the swap must be Loaded→Loaded, never Loading.
        val states = mutableListOf<TopicUiState>()
        val recorder = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { states.add(it) }
        }
        advanceTimeBy(60)
        runCurrent()
        recorder.cancel()

        assertTrue(
            "the skeleton must never show on a fast cold switch",
            states.none { it.mode is TopicUiState.Mode.Loading },
        )
        assertEquals("target", assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value).topic.title)
    }

    @Test
    fun `a slow cold switch posts the skeleton only after the grace (#910)`() = runTest {
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(2, 5, title = "departed")) },
                    flow { kotlinx.coroutines.awaitCancellation() },
                ),
            ),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        viewModel.switchToPage(3)

        val held = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("departed", held.topic.title)
        assertTrue(held.provisional)

        // Just under the grace : still holding.
        advanceTimeBy(200)
        runCurrent()
        assertTrue(viewModel.state.value.mode is TopicUiState.Mode.Loaded)

        // Past the grace with no emission : the skeleton is now legitimate.
        advanceTimeBy(100)
        runCurrent()
        assertTrue(
            "a genuinely slow load still gets its skeleton after the grace",
            viewModel.state.value.mode is TopicUiState.Mode.Loading,
        )
    }

    @Test
    fun `a failed cold switch surfaces the error instead of a stale hold (#910)`() = runTest {
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(2, 5, title = "departed")) },
                    flow { throw IOException("switch load failed") },
                ),
            ),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        viewModel.switchToPage(3)

        // A durable « displayed ≠ canonical » hold is forbidden (#907 gates) : the failed target
        // surfaces Error (Retry reloads it) instead of silently keeping the departed page.
        assertTrue(viewModel.state.value.mode is TopicUiState.Mode.Error)
        // And the cancelled grace never stomps the error with a late skeleton.
        advanceTimeBy(300)
        runCurrent()
        assertTrue(viewModel.state.value.mode is TopicUiState.Mode.Error)
    }

    @Test
    fun `a cold switch whose flow completes empty terminalizes in Error, never a stuck hold (#910)`() = runTest {
        // Gate r1 : a NORMAL completion with no emission used to leave the grace orphaned —
        // skeleton (or provisional hold) forever. The empty completion must terminalize.
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = FakeTopicRepository(
                flowsToReturn = listOf(
                    flow { emit(fakeTopic(2, 5, title = "departed")) },
                    flow { /* completes without emitting */ },
                ),
            ),
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )
        viewModel.switchToPage(3)

        assertTrue(
            "an empty completion surfaces Error for the target (Retry reloads it)",
            viewModel.state.value.mode is TopicUiState.Mode.Error,
        )
        // The cancelled grace never repaints a skeleton over the error.
        advanceTimeBy(300)
        runCurrent()
        assertTrue(viewModel.state.value.mode is TopicUiState.Mode.Error)
    }

    @Test
    fun `closing a failed search after a transition takeover reloads the canonical page (#913)`() = runTest {
        // Verdict Sol (loupe/#910) : loupe tapped during the grace, search submitted during the
        // transition (takeover kills the switch load), search FAILS, bar closed → the target must
        // be reloaded ; the old « no results → no reload » path left the departed page displayed
        // with the canonical page elsewhere and NO load in flight (the #907-forbidden state).
        val form = TopicSearchForm(hashCheck = "tok", topicId = SAMPLE_POST, cat = SAMPLE_CAT, firstnum = 1)
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5, title = "departed", searchForm = form)) },
                flow { kotlinx.coroutines.awaitCancellation() },
                flow { emit(fakeTopic(3, 5, title = "target")) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            topicSearchRepository = FakeTopicSearchRepository(error = IllegalStateException("boom")),
        )

        viewModel.switchToPage(3)
        // Mid-grace : departed page displayed (Loaded provisional), canonical = 3, loupe active.
        viewModel.send(TopicIntent.OpenSearch)
        viewModel.send(TopicIntent.SearchWordChanged("x"))
        viewModel.send(TopicIntent.SubmitSearch)
        assertEquals(TopicSearchStatus.Error, viewModel.state.value.search.status)

        viewModel.send(TopicIntent.CloseSearch)

        val landed = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals("closing the failed search reloaded the CANONICAL page", "target", landed.topic.title)
        assertEquals(3, viewModel.state.value.request.page)
    }

    @Test
    fun `pull-to-refresh is a no-op while the displayed page is not the canonical one (#910)`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5, title = "departed")) },
                flow { kotlinx.coroutines.awaitCancellation() },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        viewModel.switchToPage(3)

        // Mid-grace : the displayed Loaded is page 2, the canonical page is 3. A pull here must
        // not refresh the page the user is leaving (nor fight the in-flight switch load).
        viewModel.send(TopicIntent.Refresh)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), repository.refreshCalls)
        assertEquals(false, viewModel.state.value.isRefreshing)
    }

    private fun searchableRepo(form: TopicSearchForm): FakeTopicRepository =
        FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 5, searchForm = form)) }))

    @Suppress("LongParameterList") // test factory mirroring the ViewModel's injected dependencies.
    private fun topicViewModel(
        request: TopicRequest,
        topicRepository: TopicRepository,
        authRepository: AuthRepository,
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
        deletePostRepository: DeletePostRepository = FakeDeletePostRepository(),
        blacklistRepository: BlacklistRepository = FakeBlacklistRepository(),
        topicSearchRepository: TopicSearchRepository = FakeTopicSearchRepository(),
        searchRepository: SearchRepository = FakeSearchRepository(),
        flagRepository: FlagRepository = FakeFlagRepository(),
        // #895 étape 4 — a fresh handle per test ; the process-restore tests pass the SAME handle
        // to a second ViewModel to simulate death + recreation.
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): TopicViewModel = TopicViewModel(
        request = request,
        savedStateHandle = savedStateHandle,
        topicRepository = topicRepository,
        authRepository = authRepository,
        userPreferencesRepository = userPreferencesRepository,
        deletePostRepository = deletePostRepository,
        blacklistRepository = blacklistRepository,
        topicSearchRepository = topicSearchRepository,
        searchRepository = searchRepository,
        flagRepository = flagRepository,
    )

    private fun topicRequest(
        page: Int,
        scrollTo: Int? = null,
        resolveScrollToPage: Boolean = false,
    ): TopicRequest = TopicRequest(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
        page = page,
        scrollTo = scrollTo,
        resolveScrollToPage = resolveScrollToPage,
    )

    // ──────────────────────────────────────────────────────────────────────
    // #750 — untrusted-page resolution before the first load (email deep links)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `resolveScrollToPage resolves the real page before the first load (#750)`() = runTest {
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(37, 39)) }))
        val search = FakeSearchRepository(pageToResolve = 37)
        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 4242, resolveScrollToPage = true),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            searchRepository = search,
        )
        // The probe must carry the full (cat, post, numreponse) tuple (numreponse is per-category).
        assertEquals(listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 4242)), search.resolveCalls)
        // The load targets the RESOLVED page, never the untrusted page=1 from the email link…
        assertEquals(37, repository.calls.single().third)
        // …and the resolved page becomes the real request everywhere (indicator, retry, highlight).
        assertEquals(37, viewModel.state.value.request.page)
        assertEquals(4242, viewModel.state.value.request.scrollTo)
    }

    @Test
    fun `resolveScrollToPage falls back to the link page when the probe fails (#750)`() = runTest {
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 39)) }))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 4242, resolveScrollToPage = true),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            searchRepository = FakeSearchRepository(pageToResolve = null),
        )
        // Failed probe (null) → pre-#750 behaviour: the link's own page loads, nothing worse.
        assertEquals(1, repository.calls.single().third)
        assertEquals(1, viewModel.state.value.request.page)
    }

    @Test
    fun `resolveScrollToPage without scrollTo is a plain load, no probe (#750)`() = runTest {
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) }))
        val search = FakeSearchRepository(pageToResolve = 5)
        topicViewModel(
            request = topicRequest(page = 1, resolveScrollToPage = true),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            searchRepository = search,
        )
        assertEquals(emptyList<Triple<Int, Int, Int>>(), search.resolveCalls)
        assertEquals(1, repository.calls.single().third)
    }

    @Test
    fun `resolveScrollToPage times out on a hung probe and falls back to the link page (#750)`() = runTest {
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 39)) }))
        val hungSearch = object : SearchRepository {
            override suspend fun search(request: SearchRequest): SearchResultPage =
                throw UnsupportedOperationException("TopicViewModel never searches")

            override suspend fun resolveSearchResultPage(cat: Int, post: Int, numreponse: Int): Int? {
                // Degraded network: the probe never answers. withTimeoutOrNull(3 s) must cut it
                // (instantaneous under runTest's virtual clock) and degrade to the link page.
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 4242, resolveScrollToPage = true),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            searchRepository = hungSearch,
        )
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.calls.single().third)
        assertEquals(1, viewModel.state.value.request.page)
    }

    // ──────────────────────────────────────────────────────────────────────
    // #335 — manual pull-to-refresh
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Refresh re-fetches in place, toggles isRefreshing, emits no nav-or-scroll effect (#335)`() = runTest {
        val loaded = fakeTopic(page = 2, totalPages = 5, title = "loaded")
        val refreshed = fakeTopic(page = 2, totalPages = 5, title = "refreshed")
        val gate = CompletableDeferred<Unit>()
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        repository.refreshHook = { _, _, _ -> gate.await() }

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.Refresh)
            assertTrue("the spinner shows while the refresh is in flight", viewModel.state.value.isRefreshing)
            gate.complete(Unit)
            // A successful manual refresh keeps the reading position: no ScrollToEndOfPage and no
            // NavigateToLastPage (those are post-submit concerns, #200/#226).
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals("refreshed", (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.title)
        assertEquals(1, repository.refreshCalls.size)
    }

    @Test
    fun `a second Refresh while one is in flight is collapsed to a single network call (#335)`() = runTest {
        val loaded = fakeTopic(page = 2, totalPages = 5, title = "loaded")
        val refreshed = fakeTopic(page = 2, totalPages = 5, title = "refreshed")
        val gate = CompletableDeferred<Unit>()
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        repository.refreshHook = { _, _, _ -> gate.await() }

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.send(TopicIntent.Refresh) // suspends in the hook → isRefreshing = true
        viewModel.send(TopicIntent.Refresh) // must be ignored: a refresh is already running
        assertEquals("the in-flight guard collapses a double pull to one fetch", 1, repository.refreshCalls.size)

        gate.complete(Unit)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `Refresh failure keeps the current page and emits RefreshFailed (#335)`() = runTest {
        val loaded = fakeTopic(page = 2, totalPages = 5, title = "loaded")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshErrorToThrow = IOException("network"),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.Refresh)
            assertEquals(TopicEffect.RefreshFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(
            "the page on screen is unchanged after a failed refresh",
            "loaded",
            (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.title,
        )
    }

    @Test
    fun `Refresh is a no-op while the page is still loading (#335)`() = runTest {
        // observeTopicPage never emits → state stays Loading; a pull-to-refresh must not fire.
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { }))
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.send(TopicIntent.Refresh)

        assertTrue("no refresh call while not loaded", repository.refreshCalls.isEmpty())
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `Refresh revealing a new page updates availablePages but emits no navigation effect (#335)`() = runTest {
        // A manual refresh must surface a grown page count WITHOUT yanking the user to the last page
        // (#226 NavigateToLastPage is a post-submit concern only).
        val loaded = fakeTopic(page = 2, totalPages = 5, title = "loaded")
        val refreshed = fakeTopic(page = 2, totalPages = 6, title = "refreshed")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            viewModel.send(TopicIntent.Refresh)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals((1..6).toList(), viewModel.state.value.availablePages)
    }

    @Test
    fun `a delete cancelling an in-flight refresh clears isRefreshing (#335)`() = runTest {
        // The `finally { isRefreshing = false }` in refresh() exists precisely so another path
        // cancelling the refresh job mid-flight never strands the spinner. DeletePost →
        // refreshAfterDelete() does exactly that (loadJob?.cancel()). Pin that guarantee.
        val loaded = fakeTopic(
            page = 2,
            totalPages = 3,
            posts = listOf(fakePost(numreponse = 777, isEditable = true)),
        )
        val afterDelete = fakeTopic(page = 2, totalPages = 3, title = "after-delete")
        val gate = CompletableDeferred<Unit>()
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(afterDelete),
        )
        repository.refreshHook = { _, _, _ -> gate.await() }
        val deleteRepo = FakeDeletePostRepository(DeletePostResult.Success(deletedWholeTopic = false))

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            deletePostRepository = deleteRepo,
        )

        viewModel.send(TopicIntent.Refresh)
        assertTrue("the spinner shows while the manual refresh is in flight", viewModel.state.value.isRefreshing)

        // Let the post-delete refetch run to completion instead of suspending on the same gate.
        // The gated refresh recorded its call but never reached the refreshQueue dequeue, so the
        // `afterDelete` topic is still queued for refreshAfterDelete().
        repository.refreshHook = null
        viewModel.send(TopicIntent.DeletePost(777))

        assertFalse(
            "a delete cancelling the refresh must clear isRefreshing (finally guard)",
            viewModel.state.value.isRefreshing,
        )
        assertEquals(
            "the post-delete refetch lands the fresh page",
            "after-delete",
            (viewModel.state.value.mode as TopicUiState.Mode.Loaded).topic.title,
        )
    }

    @Test
    fun `Refresh re-arms the page+1 prefetch (#335)`() = runTest {
        // A successful manual pull on an intermediate page re-arms the page+1 warmup (the user keeps
        // reading forward) — unlike the post-submit force refresh which deliberately skips it. Expect
        // two prefetches of page+1: one on the initial load, one after the Refresh re-fetch.
        val loaded = fakeTopic(page = 2, totalPages = 5, title = "loaded")
        val refreshed = fakeTopic(page = 2, totalPages = 5, title = "refreshed")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(loaded) }),
            refreshTopicsToReturn = listOf(refreshed),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        assertEquals(
            "the initial load warms page+1 once",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.prefetches,
        )

        viewModel.send(TopicIntent.Refresh)

        assertEquals(
            "a manual refresh re-arms the page+1 warmup",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3), Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.prefetches,
        )
    }

    @Test
    fun `normal entry load does not emit ScrollToEndOfPage`() = runTest {
        // Regression guard: the bottom landing belongs to the post-submit path
        // (applySubmitResult). A normal navigation (cache-aside) must never emit it, even
        // when scrollTo is null, otherwise we would snap to the bottom on every back navigation.
        val topic = fakeTopic(page = 1, totalPages = 1, posts = listOf(fakePost(1)))
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = null),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.state.test {
            // wait for Loaded so the effect channel had a chance to receive anything
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The effects channel is BUFFERED ; if anything had been sent it would still be
        // there. tryReceive() is non-blocking so the assertion is deterministic.
        viewModel.effects.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `applySubmitResult refresh failure falls back to the cache-aside path`() = runTest {
        // Resilience: a transient network blip on the post-submit force refresh must not strand
        // the user on an error screen. The ViewModel emits PostSubmitRefreshFailed (Toast : HFR
        // DID accept the post) then falls back to observeTopicPage so the cached page is shown
        // (without the new post — but with a Retry affordance) and must NOT re-emit a bottom
        // landing on that stale cache (post 100 is the pre-submit last post).
        val cachedTopic = fakeTopic(
            page = 2,
            totalPages = 5,
            posts = listOf(fakePost(100)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(cachedTopic) }, flow { emit(cachedTopic) }),
            refreshErrorToThrow = IOException("force refresh transient failure"),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            viewModel.applySubmitResult(targetPage = 2, scrollTo = null)
            assertEquals(TopicEffect.PostSubmitRefreshFailed, awaitItem())
            // A bottom landing on the stale cache would surface here — nothing must.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(cachedTopic, mode.topic)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "Refresh attempted once before falling back",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.refreshCalls,
        )
        assertEquals(
            "Entry load then the post-failure fallback both ride observeTopicPage",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2), Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.calls,
        )
    }

    @Test
    fun `canReturnFromJump mirrors the jump chain across push, unwind and manual switch (#782)`() = runTest {
        // #895 étape 4 (PR 2) — the screen's BackHandler is driven by this flag: enabled while
        // the in-VM jump chain has frames to unwind, disabled once it is empty or a MANUAL page
        // change invalidated it (browser-like).
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { emit(fakeTopic(2, 5)) },
                flow { emit(fakeTopic(3, 5)) },
                flow { emit(fakeTopic(2, 5)) },
                flow { emit(fakeTopic(3, 5)) },
                flow { emit(fakeTopic(4, 5)) },
            ),
        )
        val viewModel = topicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )
        assertEquals(false, viewModel.state.value.canReturnFromJump)

        viewModel.goToPost(targetPage = 3, numreponse = 42)
        assertEquals(true, viewModel.state.value.canReturnFromJump)

        assertEquals(true, viewModel.returnFromJump())
        assertEquals(false, viewModel.state.value.canReturnFromJump)
        assertEquals("the unwind landed back on the departure page", 2, viewModel.state.value.request.page)

        // Re-arm a jump, then a MANUAL switch drops the whole chain (#782 browser-like rule).
        viewModel.goToPost(targetPage = 3, numreponse = 42)
        assertEquals(true, viewModel.state.value.canReturnFromJump)
        viewModel.switchToPage(4)
        assertEquals(false, viewModel.state.value.canReturnFromJump)
        assertEquals(false, viewModel.returnFromJump())
    }

    @Suppress("LongParameterList") // test builder mirroring the Topic model's fields, all defaulted.
    private fun fakeTopic(
        page: Int,
        totalPages: Int,
        title: String = "fake",
        posts: List<Post> = emptyList(),
        subcat: Int = SAMPLE_SUBCAT,
        searchForm: TopicSearchForm? = null,
    ): Topic = Topic(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
        subcat = subcat,
        title = title,
        posts = posts,
        page = page,
        totalPages = totalPages,
        isFirstPostOwner = false,
        // Postable by default: the #292 delete gate requires it, and no VM test exercises a
        // read-only topic (the previous default was the Topic model's `false`, never asserted here).
        canReply = true,
        poll = null,
        searchForm = searchForm,
    )

    private fun fakePost(numreponse: Int, isEditable: Boolean = false, author: String = "tester"): Post = Post(
        numreponse = numreponse,
        author = author,
        date = java.time.Instant.parse("2026-05-04T12:00:00Z"),
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = isEditable,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    // #809 — a full drapeau for the SAMPLE topic, as FlagRepository.findFlag would resolve it.
    private fun fakeFlag(title: String = "fake flag"): Flag = Flag(
        cat = SAMPLE_CAT,
        subcat = SAMPLE_SUBCAT,
        topicId = SAMPLE_POST,
        title = title,
        totalPages = 10,
        replyCount = 42,
        type = FlagType.CYAN,
        hasUnread = true,
        lastReadPage = 7,
        lastPostReadId = 1234L,
        firstPostAuthor = "XaT",
        lastReplyAuthor = "XaTelitte",
        lastReplyAt = "2026-05-03 12:00",
    )

    private inline fun <reified T : TopicUiState.Mode> assertMode(state: TopicUiState): T {
        val mode = state.mode
        assertTrue("expected mode ${T::class.simpleName} but was ${mode::class.simpleName}", mode is T)
        return mode as T
    }

    companion object {
        private const val SAMPLE_CAT = 13
        private const val SAMPLE_POST = 84_540
        private const val SAMPLE_SUBCAT = 432
        private const val CANCEL_TIMEOUT_MS = 2_000L
    }
}

/**
 * #750 — canned page-resolution probe. [pageToResolve] null simulates a failed probe (network
 * error / timeout / unparsable redirect); [resolveCalls] records the exact tuples asked for.
 * `search` is unused by TopicViewModel and fails loudly if something starts calling it.
 */
private class FakeSearchRepository(
    private val pageToResolve: Int? = null,
) : SearchRepository {
    val resolveCalls: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    override suspend fun search(request: SearchRequest): SearchResultPage =
        throw UnsupportedOperationException("TopicViewModel never searches")

    override suspend fun resolveSearchResultPage(cat: Int, post: Int, numreponse: Int): Int? {
        resolveCalls += Triple(cat, post, numreponse)
        return pageToResolve
    }
}

private class FakeAuthRepository(initial: AuthState) : AuthRepository {
    private val state = MutableStateFlow(initial)

    /** Simulate a live auth change (login / logout) after the ViewModel has subscribed. */
    fun emit(value: AuthState) {
        state.value = value
    }

    override fun observeAuthState() = state.asStateFlow()
    override suspend fun login(pseudo: String, password: String) =
        error("login is not exercised by TopicViewModelTest")

    override suspend fun logout() = error("logout is not exercised by TopicViewModelTest")
}

private class FakeTopicRepository(
    flowsToReturn: List<Flow<Topic>>,
    private val refreshTopicsToReturn: List<Topic> = emptyList(),
    private val refreshErrorToThrow: Throwable? = null,
) : TopicRepository {
    private val queue = ArrayDeque(flowsToReturn)
    private val refreshQueue = ArrayDeque(refreshTopicsToReturn)
    val calls: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    val refreshCalls: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    val prefetches: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    /**
     * Optional hook to suspend or fail inside `prefetch(...)`. Tests that need to
     * observe the in-flight prefetch (cancellation, hung network) install this
     * to gate the suspend until they're ready to assert. Default keeps the fake
     * fast: `prefetch` returns immediately after recording the call.
     */
    var prefetchHook: (suspend (cat: Int, post: Int, page: Int) -> Unit)? = null

    var lastForceRefresh: Boolean? = null
        private set

    /**
     * Optional hook to suspend or fail inside `refreshTopicPage(...)` — symmetric with
     * [prefetchHook]. #335 pull-to-refresh tests install a `CompletableDeferred`-backed hook to
     * observe the in-flight `isRefreshing` window and assert anti-double-trigger (a second refresh
     * while the first is suspended must not issue a second call). Default keeps the fake fast.
     */
    var refreshHook: (suspend (cat: Int, post: Int, page: Int) -> Unit)? = null

    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean): Flow<TopicPageEmission> {
        calls += Triple(cat, post, page)
        lastForceRefresh = forceRefresh
        val flow = queue.removeFirstOrNull() ?: error("No more flows queued")
        // #877 — tests enqueue plain Flow<Topic> ; the fake settles every page (provisional =
        // false) so the pre-#877 assertions keep their meaning. Provisional-specific tests use
        // [FakeStreamingEmissionTopicRepository].
        return flow.map { TopicPageEmission(it, provisional = false) }
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        refreshCalls += Triple(cat, post, page)
        refreshHook?.invoke(cat, post, page)
        refreshErrorToThrow?.let { throw it }
        return refreshQueue.removeFirstOrNull()
            ?: error("No more refresh topics queued (issue #200 post-submit force fetch path)")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        prefetches += Triple(cat, post, page)
        prefetchHook?.invoke(cat, post, page)
    }
}

private class FakeDeletePostRepository(
    private val result: DeletePostResult = DeletePostResult.Success(deletedWholeTopic = false),
) : DeletePostRepository {
    val calls = mutableListOf<EditPostContext>()

    override suspend fun deletePost(context: EditPostContext): DeletePostResult {
        calls += context
        return result
    }
}

/**
 * #809 — canned FlagRepository for the topic ViewModel. [flagToFind] is what `findFlag` resolves
 * (null = not flagged / anonymous), [removeResult] the removal outcome. Optional [findFlagGate] /
 * [removeFlagGate] hold the respective call in flight so a test can observe the Resolving / Removing
 * states and their anti double-press guard. The read APIs (`observe` / `refresh` / `clearSessionCache`)
 * fail loudly — the topic ViewModel must never touch them.
 */
private class FakeFlagRepository(
    private val flagToFind: Flag? = null,
    private val removeResult: Result<Unit> = Result.success(Unit),
) : FlagRepository {
    var findFlagCalls = 0
    var removeFlagCalls = 0
    var lastRemovedFlag: Flag? = null
    var findFlagGate: CompletableDeferred<Unit>? = null
    var removeFlagGate: CompletableDeferred<Unit>? = null

    /** Gate #809 — when set, [findFlag] throws instead of returning (resolve failure path). */
    var findFlagError: Throwable? = null

    /** Review #809 — when set, [removeFlag] throws RAW (outside its Result contract). */
    var removeFlagError: Throwable? = null

    override fun observe(type: FlagType): Flow<FlagsResult> =
        error("TopicViewModel must not observe flags")

    override suspend fun refresh(type: FlagType) = error("TopicViewModel must not refresh flags")

    override fun clearSessionCache() = error("TopicViewModel must not clear the flags cache")

    override suspend fun findFlag(cat: Int, topicId: Int): Flag? {
        findFlagCalls++
        findFlagGate?.await()
        findFlagError?.let { throw it }
        return flagToFind
    }

    override suspend fun removeFlag(flag: Flag): Result<Unit> {
        removeFlagCalls++
        lastRemovedFlag = flag
        removeFlagGate?.await()
        removeFlagError?.let { throw it }
        return removeResult
    }
}

private class FakeBlacklistRepository(
    blockedCanonicals: Set<String> = emptySet(),
) : BlacklistRepository {
    private val canonicals = MutableStateFlow(blockedCanonicals)

    override fun observeEntries(): Flow<List<BlacklistEntry>> =
        MutableStateFlow(canonicals.value.map { BlacklistEntry(it, it, 0L) })

    override fun observeBlockedCanonicals(): Flow<Set<String>> = canonicals

    // Mirror the real repository: the stored/observed keys are canonical, so block/unblock/isBlocked
    // canonicalise their raw-pseudo argument (a post author like "Alice" → "alice").
    override suspend fun isBlocked(pseudo: String): Boolean = canonicalizePseudo(pseudo) in canonicals.value

    override suspend fun block(pseudo: String) {
        canonicals.value = canonicals.value + canonicalizePseudo(pseudo)
    }

    override suspend fun unblock(pseudo: String) {
        canonicals.value = canonicals.value - canonicalizePseudo(pseudo)
    }
}

/**
 * Chantier C/B (#546) — intra-topic search fake. Returns [result] on `searchInTopic`, or throws
 * [error] when set, and records the request for assertions. For the next/previous navigation tests, a
 * [responder] (set after construction) maps each request to the reply (or throws), so a sequence of
 * fresh → step → step → end can be scripted from the request's `isStep` / `currentNum`.
 */
private class FakeTopicSearchRepository(
    private val result: Topic? = null,
    private val error: Throwable? = null,
) : TopicSearchRepository {
    val requests = mutableListOf<TopicSearchRequest>()

    /**
     * Optional per-request responder for the navigation tests. When set, it fully drives the reply
     * (returns a [Topic] or throws), letting a test script fresh/step/end from the request shape.
     */
    var responder: ((TopicSearchRequest) -> Topic)? = null

    /**
     * Optional gate to suspend inside `searchInTopic` so a test can hold the `transsearch` reply
     * in flight, drive a competing normal-load path, then release it to prove the stale-write guard.
     */
    var gate: (suspend () -> Unit)? = null

    /**
     * When `true`, the [gate] wait is run under [NonCancellable] so that cancelling [searchJob] (a
     * normal-load path taking over) does NOT short-circuit the await. The fake then truly returns its
     * (now stale) [result] AFTER the competing refresh — the only setup that actually exercises the
     * `generation != searchGeneration` guard in `submitSearch`. Without this the await is cancellable,
     * the reply is never produced, and the test would pass even with the guard removed.
     */
    var ignoreCancellation: Boolean = false

    override suspend fun searchInTopic(request: TopicSearchRequest): Topic {
        requests += request
        gate?.let { g -> if (ignoreCancellation) withContext(NonCancellable) { g() } else g() }
        responder?.let { return it(request) }
        error?.let { throw it }
        return result ?: error("FakeTopicSearchRepository has no result configured")
    }
}

private class FakeStreamingTopicRepository(
    private val source: Flow<Topic>,
) : TopicRepository {
    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean): Flow<TopicPageEmission> =
        source.map { TopicPageEmission(it, provisional = false) }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        error("refreshTopicPage not used by ViewModel under test")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        // no-op for streaming tests
    }
}

/**
 * #877 — streaming fake whose emissions carry an explicit [TopicPageEmission.provisional] flag,
 * for the provenance tests (cache emission held provisional, settled page confirmed).
 */
private class FakeStreamingEmissionTopicRepository(
    private val source: Flow<TopicPageEmission>,
    private val refreshTopicsToReturn: List<Topic> = emptyList(),
) : TopicRepository {
    private val refreshQueue = ArrayDeque(refreshTopicsToReturn)
    val refreshCalls: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean): Flow<TopicPageEmission> =
        source

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        refreshCalls += Triple(cat, post, page)
        return refreshQueue.removeFirstOrNull() ?: error("No refresh topic queued (#877 ensureSearchForm)")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        // no-op
    }
}

/**
 * No-op preferences fake for the topic ViewModel tests. Only [observeTopicTopBarAutoHide]
 * (build 89 follow-up), [observeTopicPageFabs] (#383), [observeTopicPollsExpanded] (#456),
 * [observeTopicSignatures] (#330) and [observeWritingSurfacePreset] (#806) are read by
 * [TopicViewModel] — everything else returns the DataStore default so the fake stays a thin
 * stand-in. The relevant values are constructor-injectable so tests can assert they reach state.
 */
// Internal (not private) so QuickReplyViewModelTest reuses the single fake of this wide interface.
@Suppress("LongParameterList") // one constructor knob per observed pref — grows with TopicViewModel's reads.
internal class FakeUserPreferencesRepository(
    private val topicTopBarAutoHide: Boolean = false,
    private val topicPageFabs: Boolean = true,
    private val topicPollsExpanded: Boolean = false,
    private val topicSignatures: Boolean = false,
    private val confirmBeforePosting: Boolean = false,
    // #805 — quote rendering in the composer; false mirrors the production default (inline BBCode).
    private val quoteCardsEnabled: Boolean = false,
    // #806 — writing-surface preset; SHEET mirrors the production default (0.25.1 behaviour).
    private val writingSurfacePreset: WritingSurfacePreset = WritingSurfacePreset.SHEET,
) : UserPreferencesRepository {
    override fun observeProxyConfig(): Flow<ProxyConfig> = MutableStateFlow(ProxyConfig())

    override suspend fun saveProxyConfig(config: ProxyConfig) = Unit

    override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = ProxyConfig()

    override fun observeIgnoreTopicCache(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setIgnoreTopicCache(enabled: Boolean) = Unit

    override fun observeFlagsGroupByCategory(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setFlagsGroupByCategory(enabled: Boolean) = Unit

    override fun observeFlagsHideReadCategories(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setFlagsHideReadCategories(enabled: Boolean) = Unit

    override fun observeFlagsPerTabOverride(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setFlagsPerTabOverride(enabled: Boolean) = Unit

    override fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings> =
        MutableStateFlow(FlagsViewSettings())

    override suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean) = Unit

    override suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean) = Unit

    override suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean) = Unit
    override suspend fun setFlagsMarkerStyle(style: MarkerStyle) = Unit
    override suspend fun setFlagsSingleLineTitle(enabled: Boolean) = Unit
    override suspend fun setFlagsCategoryBandStyle(style: CategoryBandStyle) = Unit
    override suspend fun setFlagsMarkerBorder(enabled: Boolean) = Unit
    override suspend fun setFlagsShowLoadingBar(enabled: Boolean) = Unit
    override fun observeAvatarAppearance(): Flow<AvatarAppearance> = MutableStateFlow(AvatarAppearance())
    override suspend fun setAvatarBorder(enabled: Boolean) = Unit
    override suspend fun setFlagsPlusLusIndicatorStyle(style: PlusLusIndicatorStyle) = Unit
    override suspend fun setFlagsGlyphStyle(style: FlagGlyphStyle) = Unit

    override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit

    override fun observeAmoledEnabled(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAmoledEnabled(enabled: Boolean) = Unit

    override fun observeTopicTopBarAutoHide(): Flow<Boolean> = MutableStateFlow(topicTopBarAutoHide)

    override suspend fun setTopicTopBarAutoHide(enabled: Boolean) = Unit

    // #312 — confirm-before-posting is irrelevant to TopicViewModel; stubbed at its default.
    override fun observeConfirmBeforePosting(): Flow<Boolean> = MutableStateFlow(confirmBeforePosting)

    override suspend fun setConfirmBeforePosting(enabled: Boolean) = Unit

    override fun observeQuoteCardsEnabled(): Flow<Boolean> = MutableStateFlow(quoteCardsEnabled)

    override suspend fun setQuoteCardsEnabled(enabled: Boolean) = Unit

    override fun observeWritingSurfacePreset(): Flow<WritingSurfacePreset> =
        MutableStateFlow(writingSurfacePreset)

    override suspend fun setWritingSurfacePreset(preset: WritingSurfacePreset) = Unit

    override fun observeShowDtSection(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setShowDtSection(enabled: Boolean) = Unit

    override fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean) = Unit

    override fun observeFlagsAutoRefresh(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setFlagsAutoRefresh(enabled: Boolean) = Unit

    override fun observeTopicPageFabs(): Flow<Boolean> = MutableStateFlow(topicPageFabs)

    override suspend fun setTopicPageFabs(enabled: Boolean) = Unit

    override fun observeMpUnreadBadge(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setMpUnreadBadge(enabled: Boolean) = Unit

    override fun observeTopicPollsExpanded(): Flow<Boolean> = MutableStateFlow(topicPollsExpanded)

    override suspend fun setTopicPollsExpanded(enabled: Boolean) = Unit

    override fun observeTopicSignatures(): Flow<Boolean> = MutableStateFlow(topicSignatures)

    override suspend fun setTopicSignatures(enabled: Boolean) = Unit

    override fun observeFoldLongQuotes(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setFoldLongQuotes(enabled: Boolean) = Unit

    override fun observeShowScrollbar(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setShowScrollbar(enabled: Boolean) = Unit

    override fun observeNavBarLabels(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setNavBarLabels(enabled: Boolean) = Unit

    override fun observeFunnyEmptyState(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setFunnyEmptyState(enabled: Boolean) = Unit

    override fun observeStartScreen(): Flow<StartScreenPreference> =
        MutableStateFlow(StartScreenPreference())

    override suspend fun setStartScreen(preference: StartScreenPreference) = Unit

    // #459 — upload provider / imgur Client-ID are irrelevant to TopicViewModel; default stubs.
    override fun observeUploadProvider(): Flow<UploadProviderId> =
        MutableStateFlow(UploadProviderId.DIBERIE)

    override suspend fun setUploadProvider(provider: UploadProviderId) = Unit

    override fun observeImgurClientId(): Flow<String> = MutableStateFlow("")

    override suspend fun setImgurClientId(clientId: String) = Unit

    override fun observeEditorImageInsert(): Flow<EditorImageInsert> =
        MutableStateFlow(EditorImageInsert.REDUCED)

    override suspend fun setEditorImageInsert(mode: EditorImageInsert) = Unit

    // #287 — reading display presets are irrelevant to TopicViewModel; stubbed at defaults.
    override fun observeDisplayDensity(): Flow<DisplayDensity> = MutableStateFlow(DisplayDensity.COMFORT)

    override suspend fun setDisplayDensity(density: DisplayDensity) = Unit

    override fun observeFontScale(): Flow<FontScalePreference> = MutableStateFlow(FontScalePreference.M)

    override suspend fun setFontScale(scale: FontScalePreference) = Unit

    override fun observeDebugBoundsOverlay(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setDebugBoundsOverlay(enabled: Boolean) = Unit

    override fun observeHideSystemNavBar(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setHideSystemNavBar(enabled: Boolean) = Unit

    override fun observeImmersiveBackButton(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setImmersiveBackButton(enabled: Boolean) = Unit

    override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> =
        MutableStateFlow(ImmersiveNavBarReveal.MANUAL)

    override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) = Unit
    override fun observeAccentColor(): Flow<AccentColor> = MutableStateFlow(AccentColor.ROSE)
    override suspend fun setAccentColor(color: AccentColor) = Unit
}
