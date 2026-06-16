package fr.forumhfr.redface2.feature.topic

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.AuthState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
    fun `scrollTo on a deleted anchor falls back to the nearest surviving post (#394)`() = runTest {
        // #394 — the « dernier lu » anchor (999) was deleted on HFR, so it is absent from the page.
        // Instead of silently landing at the top with no cue (the old bug), the ViewModel resolves
        // the nearest surviving post — the first one chronologically AFTER the deleted anchor (1001)
        // — and emits ScrollToFallbackPost so the screen can land + cue there.
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(numreponse = 990), fakePost(numreponse = 1001)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 999),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToFallbackPost(1001), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scrollTo on a deleted anchor past the page tail falls back to the last post (#394)`() = runTest {
        // #394 — the deleted anchor (999) is newer than every surviving post on the page, so there is
        // no post chronologically after it; the fallback is the page's last post (the closest
        // surviving neighbour), never a top-of-page drop.
        val topic = fakeTopic(
            page = 1,
            totalPages = 1,
            posts = listOf(fakePost(numreponse = 100), fakePost(numreponse = 200)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = topicViewModel(
            request = topicRequest(page = 1, scrollTo = 999),
            topicRepository = repository,
            authRepository = FakeAuthRepository(AuthState.Authenticated("xaat")),
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToFallbackPost(200), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scrollTo on a deleted anchor with an empty page emits no effect (#394)`() = runTest {
        // #394 — an empty page (e.g. an error fallback) has nothing to land on, so the screen keeps
        // its default top landing; the ViewModel must not emit a fallback effect.
        val topic = fakeTopic(page = 1, totalPages = 1, posts = emptyList())
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
    fun `a post-submit scrollTo missing from the page does NOT trigger the deleted-anchor fallback (#394)`() =
        runTest {
            // #394 scoping — the deleted-anchor fallback is for flag/deep-link anchors only. A
            // post-submit reload (submitSignal != null) carries its own #200 contract: if its scrollTo
            // is absent it must NOT be relocated as a « deleted anchor », so no ScrollToFallbackPost.
            val freshTopic = fakeTopic(page = 1, totalPages = 1, posts = listOf(fakePost(555)))
            val repository = FakeTopicRepository(
                flowsToReturn = emptyList(),
                refreshTopicsToReturn = listOf(freshTopic),
            )

            val viewModel = topicViewModel(
                request = topicRequest(page = 1, scrollTo = 999, submitSignal = 7L),
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
    private fun topicViewModel(
        request: TopicRequest,
        topicRepository: TopicRepository,
        authRepository: AuthRepository,
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
        deletePostRepository: DeletePostRepository = FakeDeletePostRepository(),
    ): TopicViewModel = TopicViewModel(
        request = request,
        topicRepository = topicRepository,
        authRepository = authRepository,
        userPreferencesRepository = userPreferencesRepository,
        deletePostRepository = deletePostRepository,
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

    private fun fakeTopic(
        page: Int,
        totalPages: Int,
        title: String = "fake",
        posts: List<Post> = emptyList(),
        subcat: Int = SAMPLE_SUBCAT,
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
    )

    private fun fakePost(numreponse: Int, isEditable: Boolean = false): Post = Post(
        numreponse = numreponse,
        author = "tester",
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
 * (build 89 follow-up), [observeTopicPageFabs] (#383) and [observeTopicPollsExpanded] (#456)
 * are read by [TopicViewModel] — everything else returns the DataStore default so the fake
 * stays a thin stand-in. The three relevant values are constructor-injectable so tests can
 * assert they reach state.
 */
private class FakeUserPreferencesRepository(
    private val topicTopBarAutoHide: Boolean = false,
    private val topicPageFabs: Boolean = true,
    private val topicPollsExpanded: Boolean = false,
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

    override fun observeFlagsAutoRefresh(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setFlagsAutoRefresh(enabled: Boolean) = Unit

    override fun observeTopicPageFabs(): Flow<Boolean> = MutableStateFlow(topicPageFabs)

    override suspend fun setTopicPageFabs(enabled: Boolean) = Unit

    override fun observeMpUnreadBadge(): Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setMpUnreadBadge(enabled: Boolean) = Unit

    override fun observeTopicPollsExpanded(): Flow<Boolean> = MutableStateFlow(topicPollsExpanded)

    override suspend fun setTopicPollsExpanded(enabled: Boolean) = Unit

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
}
