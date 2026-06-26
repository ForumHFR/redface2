package fr.forumhfr.redface2.feature.topic

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.topic.NoTopicSearchResultsException
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import fr.forumhfr.redface2.core.model.TopicSearchForm
import fr.forumhfr.redface2.core.model.TopicSearchRequest
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            // Beta regression: a VM created with submitSignal != null lands via forceRefreshCurrentPage
            // and NEVER calls loadCurrentPage, so the blacklist combine never ran → blockedCanonicals
            // stayed emptySet() and an already-blocked author was NOT hidden on the landing page. The
            // init collector now seeds blockedCanonicals before the force refresh computes its hidden set.
            val fresh = fakeTopic(
                page = 2,
                totalPages = 5,
                posts = listOf(fakePost(900, author = "Alice"), fakePost(901, author = "Bob")),
            )
            val repository = FakeTopicRepository(
                flowsToReturn = emptyList(),
                refreshTopicsToReturn = listOf(fresh),
            )
            val viewModel = topicViewModel(
                request = topicRequest(page = 2, submitSignal = 1_700_000_000_000L),
                topicRepository = repository,
                authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
                // Alice is ALREADY blacklisted when the VM is constructed.
                blacklistRepository = FakeBlacklistRepository(blockedCanonicals = setOf("alice")),
            )

            val mode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
            assertEquals(fresh, mode.topic)
            assertEquals(
                "an already-blacklisted author must be hidden on the force-refresh landing page",
                setOf(900),
                mode.hiddenNumreponses,
            )
            assertTrue("the landing went through the force-refresh path", repository.calls.isEmpty())
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
    fun `OpenSearch is a no-op when the loaded page exposes no usable search form`() = runTest {
        // No searchForm ⇒ canSearchInTopic=false ⇒ the bar must not open.
        val viewModel = topicViewModel(
            request = topicRequest(page = 1),
            topicRepository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(fakeTopic(1, 1)) })),
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.send(TopicIntent.OpenSearch)

        assertEquals(false, viewModel.state.value.search.isActive)
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
    ): TopicViewModel = TopicViewModel(
        request = request,
        topicRepository = topicRepository,
        authRepository = authRepository,
        userPreferencesRepository = userPreferencesRepository,
        deletePostRepository = deletePostRepository,
        blacklistRepository = blacklistRepository,
        topicSearchRepository = topicSearchRepository,
    )

    private fun topicRequest(
        page: Int,
        scrollTo: Int? = null,
        submitSignal: Long? = null,
        postSubmitOverflowLanding: Boolean = false,
    ): TopicRequest = TopicRequest(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
        page = page,
        scrollTo = scrollTo,
        submitSignal = submitSignal,
        postSubmitOverflowLanding = postSubmitOverflowLanding,
    )

    // ──────────────────────────────────────────────────────────────────────
    // Issue #200 — post-submit force refresh path
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `submitSignal triggers a force refresh instead of the cache-aside path`() = runTest {
        // Reply / quote / edit / edit-FP landing: the navigation host bumped submitSignal
        // so the ViewModel must skip observeTopicPage (cache-aside) and call refreshTopicPage
        // directly, otherwise the user would see a stale page that doesn't include the post
        // they just published.
        val freshTopic = fakeTopic(page = 2, totalPages = 5, posts = listOf(fakePost(987)))
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(freshTopic),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2, submitSignal = 1_700_000_000_000L),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(freshTopic, mode.topic)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "Force refresh path must hit refreshTopicPage, not observeTopicPage",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.refreshCalls,
        )
        assertTrue(
            "observeTopicPage must NOT be called when submitSignal is non-null",
            repository.calls.isEmpty(),
        )
    }

    @Test
    fun `submitSignal with scrollTo emits ScrollToPost after the force refresh`() = runTest {
        // Quote / edit / edit-FP path: the parser extracted #t{numreponse} so the navigation
        // host passed scrollTo through. The ViewModel must emit ScrollToPost(target) once
        // the force-refreshed page is loaded and contains the target post.
        val targetNumreponse = 2_523_833
        val freshTopic = fakeTopic(
            page = 1,
            totalPages = 3,
            posts = listOf(fakePost(2_523_829), fakePost(targetNumreponse)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(freshTopic),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = targetNumreponse, submitSignal = 42L),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToPost(targetNumreponse), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submitSignal without scrollTo emits ScrollToEndOfPage so plain reply lands on the new post`() = runTest {
        // Plain reply path: HFR anchored #bas so the parser left scrollTo null. The ViewModel
        // emits ScrollToEndOfPage so the screen scrolls to the last post (the one just
        // published) rather than letting the user wonder where their reply went.
        val freshTopic = fakeTopic(
            page = 20,
            totalPages = 20,
            posts = listOf(fakePost(1), fakePost(2), fakePost(3)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(freshTopic),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 20, scrollTo = null, submitSignal = 99L),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToEndOfPage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submitSignal without scrollTo on an overflowed reply emits NavigateToLastPage (#226)`() = runTest {
        // #226 — the plain reply was submitted from page 20's form, but it overflowed onto a freshly
        // created page 21. HFR anchored the form's page (20) so the force refresh of page 20 reports
        // an up-to-date totalPages = 21 > request.page = 20. The new post lives on page 21, not here,
        // so the ViewModel must re-route there (NavigateToLastPage) rather than ScrollToEndOfPage on
        // the stale page 20 (which would scroll to the pre-overflow last post and confuse the user).
        val overflowedTopic = fakeTopic(
            page = 20,
            totalPages = 21,
            posts = listOf(fakePost(1), fakePost(2), fakePost(3)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(overflowedTopic),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 20, scrollTo = null, submitSignal = 123L),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.NavigateToLastPage(21), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `overflow landing force-refreshes and scrolls to end without re-redirecting (#226)`() = runTest {
        // #226 — the host re-routed us onto the freshly created last page (21) with a fresh
        // submitSignal AND postSubmitOverflowLanding = true. The force refresh still runs (submitSignal
        // != null) so the page is never a stale cache-aside row — the original #226 failure — but the
        // landing flag means we stop here: emit ScrollToEndOfPage to surface the new reply, NOT another
        // NavigateToLastPage. (totalPages == request.page, the normal post-overflow shape.)
        val landedTopic = fakeTopic(
            page = 21,
            totalPages = 21,
            posts = listOf(fakePost(40), fakePost(41)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(landedTopic),
        )

        val viewModel = topicViewModel(
            request = topicRequest(
                page = 21,
                scrollTo = null,
                submitSignal = 456L,
                postSubmitOverflowLanding = true,
            ),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToEndOfPage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `overflow landing does not chase a moving tail when a concurrent post grows totalPages (#226)`() =
        runTest {
            // #226 anti-chase — on the overflow landing (page 21, flag set), a concurrent poster
            // created page 22 during our refresh, so the fresh page reports totalPages = 22 >
            // request.page = 21. Without the flag the ViewModel would NavigateToLastPage(22) and keep
            // chasing the moving tail. The flag pins us here: ScrollToEndOfPage, never NavigateToLastPage.
            val landedTopic = fakeTopic(
                page = 21,
                totalPages = 22,
                posts = listOf(fakePost(40), fakePost(41)),
            )
            val repository = FakeTopicRepository(
                flowsToReturn = emptyList(),
                refreshTopicsToReturn = listOf(landedTopic),
            )

            val viewModel = topicViewModel(
                request = topicRequest(
                    page = 21,
                    scrollTo = null,
                    submitSignal = 789L,
                    postSubmitOverflowLanding = true,
                ),
                topicRepository = repository,
                authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
            )

            viewModel.effects.test {
                assertEquals(TopicEffect.ScrollToEndOfPage, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
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
    fun `normal load without submitSignal does not emit ScrollToEndOfPage`() = runTest {
        // Regression guard: ScrollToEndOfPage is gated on submitSignal != null. A normal
        // deep-link navigation (cache-aside) must never emit it, even when scrollTo is null,
        // otherwise we would snap to the bottom on every back navigation.
        val topic = fakeTopic(page = 1, totalPages = 1, posts = listOf(fakePost(1)))
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = null, submitSignal = null),
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
    fun `submitSignal refresh failure falls back to the cache-aside path`() = runTest {
        // Resilience: a transient network blip on the force refresh must not strand the user
        // on an error screen. The ViewModel falls back to observeTopicPage so the cached
        // page is shown (without the new post — but with a Retry affordance).
        val cachedTopic = fakeTopic(
            page = 2,
            totalPages = 5,
            // Stale cache: the pre-submit "last post" is post 100. If the VM erroneously
            // emitted ScrollToEndOfPage after the fallback, the user would be scrolled to
            // post 100 thinking it's their fresh reply — that's the bug we guard against.
            posts = listOf(fakePost(100)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(cachedTopic) }),
            refreshErrorToThrow = IOException("force refresh transient failure"),
        )

        val viewModel = topicViewModel(
            request = topicRequest(page = 2, submitSignal = 7L),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        // The first effect emitted on the failure path must be PostSubmitRefreshFailed so the
        // screen surfaces a Toast (cf. TopicScreen.kt) telling the user HFR did accept their post
        // even though the local refresh blipped. The fallback to observeTopicPage must NOT then
        // re-emit ScrollToEndOfPage on the stale cache.
        viewModel.effects.test {
            assertEquals(TopicEffect.PostSubmitRefreshFailed, awaitItem())
            // expectNoEvents() would race with cache emission; we settle by ensuring no
            // further effect lands within the test scheduler's idle.
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
            "Fallback path must hit observeTopicPage after the failure",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.calls,
        )
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

    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean): Flow<Topic> {
        calls += Triple(cat, post, page)
        lastForceRefresh = forceRefresh
        return queue.removeFirstOrNull() ?: error("No more flows queued")
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
    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean): Flow<Topic> = source

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        error("refreshTopicPage not used by ViewModel under test")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        // no-op for streaming tests
    }
}

/**
 * No-op preferences fake for the topic ViewModel tests. Only [observeTopicTopBarAutoHide]
 * (build 89 follow-up), [observeTopicPageFabs] (#383), [observeTopicPollsExpanded] (#456) and
 * [observeTopicSignatures] (#330) are read by [TopicViewModel] — everything else returns the
 * DataStore default so the fake stays a thin stand-in. The relevant values are
 * constructor-injectable so tests can assert they reach state.
 */
private class FakeUserPreferencesRepository(
    private val topicTopBarAutoHide: Boolean = false,
    private val topicPageFabs: Boolean = true,
    private val topicPollsExpanded: Boolean = false,
    private val topicSignatures: Boolean = false,
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

    override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit

    override fun observeAmoledEnabled(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAmoledEnabled(enabled: Boolean) = Unit

    override fun observeTopicTopBarAutoHide(): Flow<Boolean> = MutableStateFlow(topicTopBarAutoHide)

    override suspend fun setTopicTopBarAutoHide(enabled: Boolean) = Unit

    // #312 — confirm-before-posting is irrelevant to TopicViewModel; stubbed at its default.
    override fun observeConfirmBeforePosting(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setConfirmBeforePosting(enabled: Boolean) = Unit

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
