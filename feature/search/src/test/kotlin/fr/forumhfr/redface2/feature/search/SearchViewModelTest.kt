package fr.forumhfr.redface2.feature.search

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchRequest
import fr.forumhfr.redface2.core.model.search.SearchResultPage
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import fr.forumhfr.redface2.core.model.search.SearchTopicResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2G-A/B (#150 partiel) — tests for [SearchViewModel].
 *
 * Repository is mocked with MockK ; the VM's contract is exercised via its
 * StateFlow + the `submit(intent)` surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val repo: SearchRepository = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `QueryChanged updates the query in state`() = runTest {
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        assertEquals("kotlin", vm.state.value.query)
        assertFalse(vm.state.value.hasSearched)
    }

    @Test
    fun `Submit with a blank query does not call the repository`() = runTest {
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("   "))
        vm.submit(SearchIntent.Submit)
        coVerify(exactly = 0) { repo.search(any()) }
        // `hasSearched` stays false so the screen keeps the idle hint.
        assertFalse(vm.state.value.hasSearched)
    }

    @Test
    fun `Submit success populates results pivot and selectedCategory`() = runTest {
        coEvery { repo.search(any()) } returns fakePage(
            topics = listOf(fakeTopic(topicId = 1, title = "Hello kotlin")),
            pivot = listOf(SearchPivotCategory(id = 10, label = "Programmation", isSelected = true)),
        )
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        vm.submit(SearchIntent.Submit)

        val final = vm.state.value
        assertTrue(final.hasSearched)
        assertFalse(final.isLoading)
        assertEquals(1, final.results.size)
        assertEquals("Hello kotlin", final.results.first().title)
        assertEquals(10, final.selectedCategory?.id)
        assertNull(final.errorMessage)
    }

    @Test
    fun `Submit no-results leaves results empty without an error`() = runTest {
        coEvery { repo.search(any()) } returns fakePage(topics = emptyList(), pivot = emptyList())
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("xqzkbm9wj4abc"))
        vm.submit(SearchIntent.Submit)

        val final = vm.state.value
        assertTrue("hasSearched flips to true so the empty state can render", final.hasSearched)
        assertEquals(emptyList<Any>(), final.results)
        assertNull("no-result is NOT an error", final.errorMessage)
    }

    @Test
    fun `repository IOException sets the network error kind and preserves the query`() = runTest {
        coEvery { repo.search(any()) } throws IOException("HFR search request failed: HFR returned 500")
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        vm.submit(SearchIntent.Submit)

        val final = vm.state.value
        assertEquals(HfrErrorKind.Network, final.errorMessage)
        assertEquals("kotlin", final.query)
        assertFalse(final.isLoading)
    }

    @Test
    fun `repository HfrServerException sets the ServerDown error kind`() = runTest {
        // #324 — DefaultSearchRepository lets the typed exception traverse (URL redacted);
        // a 5xx must surface as « HFR est en panne », not as a network problem.
        coEvery { repo.search(any()) } throws HfrServerException(code = 500, url = "<redacted>")
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        vm.submit(SearchIntent.Submit)

        val final = vm.state.value
        assertEquals(HfrErrorKind.ServerDown, final.errorMessage)
        assertFalse(final.isLoading)
    }

    @Test
    fun `repository SessionExpiredException sets the Other error kind not Network`() = runTest {
        // #324 — search has no dedicated session treatment; an expired session must not be
        // presented as a connectivity cut (classifyHfrError → Other).
        coEvery { repo.search(any()) } throws SessionExpiredException("<redacted>")
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        vm.submit(SearchIntent.Submit)

        assertEquals(HfrErrorKind.Other, vm.state.value.errorMessage)
    }

    @Test
    fun `Retry re-runs the last submitted query`() = runTest {
        var attempt = 0
        coEvery { repo.search(any()) } answers {
            attempt += 1
            if (attempt == 1) throw IOException("offline")
            else fakePage(topics = listOf(fakeTopic(2, "Recovered")), pivot = emptyList())
        }
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        vm.submit(SearchIntent.Submit) // first attempt throws
        assertEquals(HfrErrorKind.Network, vm.state.value.errorMessage)

        // User types a NEW value in the field but does NOT submit — Retry must
        // still re-use the previously submitted query, not the current field value.
        vm.submit(SearchIntent.QueryChanged("zzz"))
        vm.submit(SearchIntent.Retry)

        coVerify(exactly = 2) { repo.search(SearchRequest(query = "kotlin", category = SearchCategoryScope.All)) }
        assertEquals("kotlin", vm.state.value.query)
        assertEquals(1, vm.state.value.results.size)
        assertEquals("Recovered", vm.state.value.results.first().title)
    }

    @Test
    fun `CategorySelected re-runs the search scoped to the picked category`() = runTest {
        coEvery { repo.search(SearchRequest(query = "android", category = SearchCategoryScope.All)) } returns
            fakePage(
                topics = listOf(fakeTopic(1, "global hit", cat = 1)),
                pivot = listOf(
                    SearchPivotCategory(id = 1, label = "Hardware", isSelected = true),
                    SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
                ),
            )
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(99, "prog hit", cat = 10)),
            pivot = listOf(
                SearchPivotCategory(id = 1, label = "Hardware", isSelected = false),
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = true),
            ),
        )

        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("android"))
        vm.submit(SearchIntent.Submit)

        vm.submit(
            SearchIntent.CategorySelected(
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )

        val final = vm.state.value
        assertEquals(10, final.selectedCategory?.id)
        assertEquals("prog hit", final.results.first().title)
    }

    @Test
    fun `TextScopeSelected re-runs an existing search across all categories`() = runTest {
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.All,
                    textScope = SearchTextScope.TitlesAndPosts,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(1, "mixed hit", cat = 1)),
            pivot = emptyList(),
        )
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.All,
                    textScope = SearchTextScope.TitlesOnly,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(2, "title hit", cat = 1)),
            pivot = emptyList(),
        )

        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("android"))
        vm.submit(SearchIntent.Submit)
        vm.submit(SearchIntent.TextScopeSelected(SearchTextScope.TitlesOnly))

        val final = vm.state.value
        assertEquals(SearchTextScope.TitlesOnly, final.textScope)
        assertEquals("title hit", final.results.first().title)
        coVerify(exactly = 1) {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.All,
                    textScope = SearchTextScope.TitlesOnly,
                ),
            )
        }
    }

    @Test
    fun `TextScopeSelected preserves the last submitted category`() = runTest {
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.All,
                    textScope = SearchTextScope.TitlesAndPosts,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(1, "global hit", cat = 1)),
            pivot = listOf(
                SearchPivotCategory(id = 1, label = "Hardware", isSelected = true),
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
                    textScope = SearchTextScope.TitlesAndPosts,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(2, "scoped mixed hit", cat = 10)),
            pivot = emptyList(),
        )
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
                    textScope = SearchTextScope.PostsOnly,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(3, "scoped post hit", cat = 10)),
            pivot = emptyList(),
        )

        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("android"))
        vm.submit(SearchIntent.Submit)
        vm.submit(
            SearchIntent.CategorySelected(
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )
        vm.submit(SearchIntent.TextScopeSelected(SearchTextScope.PostsOnly))

        val final = vm.state.value
        assertEquals(SearchTextScope.PostsOnly, final.textScope)
        assertEquals("scoped post hit", final.results.first().title)
        coVerify(exactly = 1) {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
                    textScope = SearchTextScope.PostsOnly,
                ),
            )
        }
    }

    @Test
    fun `CategorySelected preserves the last submitted text scope`() = runTest {
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.All,
                    textScope = SearchTextScope.PostsOnly,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(1, "global post hit", cat = 1)),
            pivot = listOf(
                SearchPivotCategory(id = 1, label = "Hardware", isSelected = true),
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )
        coEvery {
            repo.search(
                SearchRequest(
                    query = "android",
                    category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
                    textScope = SearchTextScope.PostsOnly,
                ),
            )
        } returns fakePage(
            topics = listOf(fakeTopic(99, "scoped post hit", cat = 10)),
            pivot = emptyList(),
        )

        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("android"))
        vm.submit(SearchIntent.TextScopeSelected(SearchTextScope.PostsOnly))
        vm.submit(SearchIntent.Submit)
        vm.submit(
            SearchIntent.CategorySelected(
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )

        val final = vm.state.value
        assertEquals(SearchTextScope.PostsOnly, final.textScope)
        assertEquals("scoped post hit", final.results.first().title)
    }

    @Test
    fun `a newer submit cancels the in-flight previous one`() = runTest {
        val gate = CompletableDeferred<SearchResultPage>()
        coEvery { repo.search(SearchRequest(query = "kot", category = SearchCategoryScope.All)) } coAnswers {
            // Hang until the test releases the gate, then resolve.
            gate.await()
        }
        coEvery { repo.search(SearchRequest(query = "kotlin", category = SearchCategoryScope.All)) } returns
            fakePage(topics = listOf(fakeTopic(1, "kotlin hit")), pivot = emptyList())

        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.state.test {
            // initial idle state
            skipItems(1)
            vm.submit(SearchIntent.QueryChanged("kot"))
            vm.submit(SearchIntent.Submit)
            // Newer submit overrides the in-flight one.
            vm.submit(SearchIntent.QueryChanged("kotlin"))
            vm.submit(SearchIntent.Submit)
            // Releasing the gate would have completed the OLDER call, but it was
            // cancelled by the second submit — its result must NOT land in state.
            gate.complete(
                fakePage(topics = listOf(fakeTopic(99, "stale result")), pivot = emptyList()),
            )
            val finalState = expectMostRecentItem()
            assertEquals(1, finalState.results.size)
            assertEquals(
                "newer query result must win over the cancelled older one",
                "kotlin hit",
                finalState.results.first().title,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `typing a new query cancels an in-flight search and clears stale results`() = runTest {
        val gate = CompletableDeferred<SearchResultPage>()
        coEvery { repo.search(SearchRequest(query = "android", category = SearchCategoryScope.All)) } coAnswers {
            gate.await()
        }

        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("android"))
        vm.submit(SearchIntent.Submit)
        assertTrue(vm.state.value.isLoading)

        vm.submit(SearchIntent.QueryChanged("kotlin"))

        val typedState = vm.state.value
        assertEquals("kotlin", typedState.query)
        assertFalse("typing a new query should stop the loading state", typedState.isLoading)
        assertFalse("typing a new query returns to the idle state", typedState.hasSearched)
        assertEquals(emptyList<SearchTopicResult>(), typedState.results)
        assertEquals(emptyList<SearchPivotCategory>(), typedState.pivotCategories)

        // Completing the cancelled call must not repopulate results under the
        // new, not-yet-submitted query.
        gate.complete(fakePage(topics = listOf(fakeTopic(99, "stale android hit")), pivot = emptyList()))
        assertEquals("kotlin", vm.state.value.query)
        assertEquals(emptyList<SearchTopicResult>(), vm.state.value.results)
    }

    // #277 — opening a result resolves the real page through the repository before
    // emitting the navigation effect. The search href always carries page=1, so the
    // resolved page is the only trustworthy navigation input for content matches.

    @Test
    fun `OpenResult with a matched numreponse emits the resolved page`() = runTest {
        coEvery { repo.resolveSearchResultPage(cat = 23, post = 35421, numreponse = 2786758) } returns 3
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 35421, title = "Redface dev", cat = 23, page = 1, numreponse = 2786758)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            assertEquals(
                SearchEffect.NavigateToTopic(cat = 23, post = 35421, page = 3, scrollTo = 2786758),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `OpenResult falls back to the href page when resolution returns null`() = runTest {
        coEvery { repo.resolveSearchResultPage(any(), any(), any()) } returns null
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 35421, title = "Redface dev", cat = 23, page = 2, numreponse = 2786758)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            assertEquals(
                SearchEffect.NavigateToTopic(cat = 23, post = 35421, page = 2, scrollTo = 2786758),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `OpenResult falls back to page 1 when resolution throws and the href has no page`() = runTest {
        coEvery { repo.resolveSearchResultPage(any(), any(), any()) } throws IOException("offline")
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 35421, title = "Redface dev", cat = 23, page = null, numreponse = 2786758)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            // Network failure must degrade to the pre-#277 behaviour, never block navigation.
            assertEquals(
                SearchEffect.NavigateToTopic(cat = 23, post = 35421, page = 1, scrollTo = 2786758),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `OpenResult on a title-only row emits page 1 without calling the repository`() = runTest {
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 777, title = "Title-only hit", cat = 10)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            assertEquals(
                SearchEffect.NavigateToTopic(cat = 10, post = 777, page = 1, scrollTo = null),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { repo.resolveSearchResultPage(any(), any(), any()) }
    }

    @Test
    fun `OpenResult ignores further taps while a resolution is in flight`() = runTest {
        val gate = CompletableDeferred<Int?>()
        coEvery { repo.resolveSearchResultPage(any(), any(), any()) } coAnswers { gate.await() }
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 35421, title = "Redface dev", cat = 23, page = 1, numreponse = 2786758)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            // Double (and triple) tap while the first resolution hangs : ignored, no queue.
            vm.submit(SearchIntent.OpenResult(result))
            vm.submit(SearchIntent.OpenResult(result))
            gate.complete(3)

            assertEquals(
                SearchEffect.NavigateToTopic(cat = 23, post = 35421, page = 3, scrollTo = 2786758),
                awaitItem(),
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repo.resolveSearchResultPage(any(), any(), any()) }
    }

    @Test
    fun `OpenResult falls back to the href page when resolution exceeds the probe timeout`() = runTest {
        // A resolution that never completes : the 3 s probe deadline (virtual time)
        // must release the guard and degrade to the pre-#277 href navigation.
        coEvery { repo.resolveSearchResultPage(any(), any(), any()) } coAnswers {
            CompletableDeferred<Int?>().await()
        }
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 35421, title = "Redface dev", cat = 23, page = 1, numreponse = 2786758)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            assertEquals(
                SearchEffect.NavigateToTopic(cat = 23, post = 35421, page = 1, scrollTo = 2786758),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `OpenResult guard resets after a completed resolution`() = runTest {
        coEvery { repo.resolveSearchResultPage(any(), any(), any()) } returns 3
        val vm = SearchViewModel(repo, initialPseudo = null)
        val result = fakeTopic(topicId = 35421, title = "Redface dev", cat = 23, page = 1, numreponse = 2786758)

        vm.effects.test {
            vm.submit(SearchIntent.OpenResult(result))
            awaitItem()
            // The first resolution completed — a second tap must go through again.
            vm.submit(SearchIntent.OpenResult(result))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { repo.resolveSearchResultPage(any(), any(), any()) }
    }

    // Author filter (`pseud=`) — query-only, author-only, and combined searches are all
    // legal ; at least one of the two must be non-blank.

    @Test
    fun `Submit with author only calls the repository with the pseudo and an empty query`() = runTest {
        coEvery { repo.search(any()) } returns fakePage(
            topics = listOf(fakeTopic(1, "participated topic")),
            pivot = emptyList(),
        )
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.PseudoChanged("Lt Ripley"))
        vm.submit(SearchIntent.Submit)

        coVerify(exactly = 1) {
            repo.search(
                SearchRequest(query = "", category = SearchCategoryScope.All, pseudo = "Lt Ripley"),
            )
        }
        assertTrue(vm.state.value.hasSearched)
        assertEquals(1, vm.state.value.results.size)
    }

    @Test
    fun `Submit with blank query AND blank pseudo does not call the repository`() = runTest {
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("   "))
        vm.submit(SearchIntent.PseudoChanged("  "))
        vm.submit(SearchIntent.Submit)
        coVerify(exactly = 0) { repo.search(any()) }
        assertFalse(vm.state.value.hasSearched)
    }

    @Test
    fun `a non-blank initialPseudo fires the author-only search at construction`() = runTest {
        coEvery { repo.search(any()) } returns fakePage(
            topics = listOf(fakeTopic(1, "recent post topic")),
            pivot = emptyList(),
        )
        val vm = SearchViewModel(repo, initialPseudo = "Lt Ripley")

        coVerify(exactly = 1) {
            repo.search(
                SearchRequest(query = "", category = SearchCategoryScope.All, pseudo = "Lt Ripley"),
            )
        }
        val state = vm.state.value
        assertEquals("Lt Ripley", state.pseudo)
        assertTrue(state.hasSearched)
        assertEquals("recent post topic", state.results.first().title)
    }

    @Test
    fun `a null initialPseudo starts idle without any repository call`() = runTest {
        val vm = SearchViewModel(repo, initialPseudo = null)
        coVerify(exactly = 0) { repo.search(any()) }
        assertFalse(vm.state.value.hasSearched)
    }

    @Test
    fun `PseudoChanged invalidates the displayed results like QueryChanged`() = runTest {
        coEvery { repo.search(any()) } returns fakePage(
            topics = listOf(fakeTopic(1, "hit")),
            pivot = emptyList(),
        )
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.QueryChanged("kotlin"))
        vm.submit(SearchIntent.Submit)
        assertEquals(1, vm.state.value.results.size)

        vm.submit(SearchIntent.PseudoChanged("Lt Ripley"))

        val typed = vm.state.value
        assertEquals("Lt Ripley", typed.pseudo)
        assertEquals("the un-submitted query stays in its field", "kotlin", typed.query)
        assertFalse("editing the author filter orphans the displayed results", typed.hasSearched)
        assertEquals(emptyList<SearchTopicResult>(), typed.results)
    }

    @Test
    fun `Retry re-runs the last author-only search with its pseudo`() = runTest {
        var attempt = 0
        coEvery { repo.search(any()) } answers {
            attempt += 1
            if (attempt == 1) throw IOException("offline")
            else fakePage(topics = listOf(fakeTopic(2, "Recovered")), pivot = emptyList())
        }
        val vm = SearchViewModel(repo, initialPseudo = null)
        vm.submit(SearchIntent.PseudoChanged("Lt Ripley"))
        vm.submit(SearchIntent.Submit)
        assertEquals(HfrErrorKind.Network, vm.state.value.errorMessage)

        vm.submit(SearchIntent.Retry)

        coVerify(exactly = 2) {
            repo.search(
                SearchRequest(query = "", category = SearchCategoryScope.All, pseudo = "Lt Ripley"),
            )
        }
        assertEquals("Recovered", vm.state.value.results.first().title)
    }

    @Test
    fun `CategorySelected keeps the author filter of an author-only search`() = runTest {
        coEvery { repo.search(any()) } returns fakePage(
            topics = listOf(fakeTopic(1, "pivot hit")),
            pivot = listOf(
                SearchPivotCategory(id = 1, label = "Hardware", isSelected = true),
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )
        val vm = SearchViewModel(repo, initialPseudo = "Lt Ripley")
        vm.submit(
            SearchIntent.CategorySelected(
                SearchPivotCategory(id = 10, label = "Programmation", isSelected = false),
            ),
        )

        coVerify(exactly = 1) {
            repo.search(
                SearchRequest(
                    query = "",
                    category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
                    pseudo = "Lt Ripley",
                ),
            )
        }
    }

    private fun fakeTopic(
        topicId: Int,
        title: String,
        cat: Int = 10,
        page: Int? = null,
        numreponse: Int? = null,
    ): SearchTopicResult = SearchTopicResult(
        cat = cat,
        topicId = topicId,
        title = title,
        author = "someone",
        replyCount = 0,
        viewCount = 0,
        lastReplyAt = "01-01-2026",
        lastReplyAuthor = "someone-else",
        topicUrl = "/hfr/x/y/-sujet_${topicId}_1.htm",
        categorySlug = "x",
        subcategorySlug = "y",
        isLocked = false,
        page = page,
        numreponse = numreponse,
        matchedExcerpt = null,
    )

    private fun fakePage(
        topics: List<SearchTopicResult>,
        pivot: List<SearchPivotCategory>,
    ): SearchResultPage = SearchResultPage(
        query = "test",
        requestedCategory = SearchCategoryScope.All,
        selectedCategory = pivot.firstOrNull { it.isSelected },
        pivotCategories = pivot,
        topics = topics,
        currentPage = 1,
        totalPages = 1,
    )
}
