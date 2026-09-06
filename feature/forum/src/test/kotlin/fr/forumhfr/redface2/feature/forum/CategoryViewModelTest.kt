package fr.forumhfr.redface2.feature.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `canCreateTopic is true when the auth state is Authenticated`() = runTest {
        // Phase 2E #149 — the « Nouveau topic » FAB is the only user-visible
        // surface gated on the auth state. The other 16 tests in this class
        // exercise the default `Anonymous` path ; this one pins the positive
        // path so a future refactor cannot accidentally hide the FAB on a
        // signed-in account.
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(initial = AuthState.Authenticated("xat")),
            userPreferencesRepository = FakePreferences(),
        )
        vm.uiState.test {
            // Drain warm-up emissions until canCreateTopic flips on (the
            // initial value is `false` because the StateFlow seed is computed
            // before the auth flow has emitted).
            val authenticated = awaitContent { it.canCreateTopic }
            assertTrue("FAB must be visible on Authenticated", authenticated.canCreateTopic)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canCreateTopic stays false on Anonymous auth state`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(initial = AuthState.Anonymous),
            userPreferencesRepository = FakePreferences(),
        )
        vm.uiState.test {
            // The initial StateFlow seed is `canCreateTopic = false` already ;
            // we still drain one item to make the assertion explicit on the
            // post-warm-up value.
            assertFalse("FAB must stay hidden on Anonymous", awaitItem().canCreateTopic)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectSubcategory swaps the topic listing and resets to page 1`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            // Initial state — Loading on subcategories AND topics
            val initial = awaitItem()
            assertEquals(23, initial.cat)
            assertNull(initial.selectedSubcat)
            assertEquals(1, initial.page)

            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(cat = 23, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            // Drain warm-up emissions until both subcategories and topics are Content.
            awaitContent { it.subcategories is SubcategoriesUiState.Content && it.topics is TopicsUiState.Content }

            vm.selectSubcategory(550)

            // After selection, topics goes Loading until the new subcat list arrives.
            val afterSelect = awaitItem()
            assertEquals(550, afterSelect.selectedSubcat)
            assertEquals(1, afterSelect.page)

            repo.emitTopicList(cat = 23, subcat = 550, page = 1, result = ForumResult.Success(EMPTY_PAGE))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initialPage from CategoryRequest seeds the page state`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550, initialPage = 4),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        // Initial uiState carries the initialPage straight through (no warm-up needed —
        // the StateFlow's initialValue is built off the request).
        assertEquals(4, vm.uiState.value.page)
    }

    @Test
    fun `selectPage moves to the requested page`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 13, initialSubcat = 422),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            // Drain until topics state is Content for page 1, then jump to page 2.
            repo.emitSubcategories(ForumResult.Success(emptyList()))
            repo.emitTopicList(cat = 13, subcat = 422, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            var current = awaitContent { it.topics is TopicsUiState.Content }
            assertEquals(1, current.page)

            vm.selectPage(2)
            // Page advance triggers a new flow subscription — emit content for page 2.
            repo.emitTopicList(cat = 13, subcat = 422, page = 2, result = ForumResult.Success(EMPTY_PAGE))

            current = awaitContent { it.page == 2 && it.topics is TopicsUiState.Content }
            assertEquals(2, current.page)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pageCount reflects topics totalTopics divided by resultsPerPage`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            repo.emitSubcategories(ForumResult.Success(emptyList()))
            // 130 topics / 50-per-page → 3 pages.
            repo.emitTopicList(
                cat = 23,
                subcat = 550,
                page = 1,
                result = ForumResult.Success(
                    EMPTY_PAGE.copy(totalTopics = 130, resultsPerPage = 50),
                ),
            )

            val current = awaitContent { it.topics is TopicsUiState.Content }
            assertEquals(3, current.pageCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categoryName is derived from observeCategories matching cat`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            // Initial Loading uiState — no categoryName yet.
            assertNull(awaitItem().categoryName)

            repo.emitCategories(
                ForumResult.Success(
                    listOf(
                        Category(id = 23, name = "Technologies Mobiles", forceSubcat = false, subcategoryCount = 5),
                        Category(id = 30, name = "Electronique", forceSubcat = false, subcategoryCount = 3),
                    ),
                ),
            )

            val current = awaitContent { it.categoryName == "Technologies Mobiles" }
            assertEquals("Technologies Mobiles", current.categoryName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categoryName stays null when categories list does not contain cat`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 999, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitCategories(
                ForumResult.Success(
                    listOf(
                        Category(id = 23, name = "Technologies Mobiles", forceSubcat = false, subcategoryCount = 5),
                    ),
                ),
            )
            // No emission with categoryName != null is expected — we just ensure the
            // state remains coherent (cat=999, name=null) when topics emit too.
            repo.emitTopicList(cat = 999, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))
            val current = awaitContent { it.topics is TopicsUiState.Content }
            assertNull(current.categoryName)
            assertEquals(999, current.cat)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh forwards to ForumRepository for subcategories AND current topic list`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.refresh()

        assertTrue(repo.refreshSubcategoriesCalls.contains(23))
        assertEquals(listOf(Triple(23, 550, 1)), repo.refreshTopicListCalls)
    }

    @Test
    fun `refresh forwards the current page and selectedSubcat after the user moved`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null, initialPage = 1),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        // Move state to (subcat=550, page=2) before the user pulls to refresh.
        vm.selectSubcategory(550)
        vm.selectPage(2)

        vm.refresh()

        assertTrue(repo.refreshSubcategoriesCalls.contains(23))
        assertEquals(listOf(Triple(23, 550, 2)), repo.refreshTopicListCalls)
    }

    @Test
    fun `isRefreshing flips true during refresh and false after`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        // Force at least one collector on uiState so the underlying combine starts
        // observing isRefreshing — without this, vm.uiState.value would stay frozen
        // on the StateFlow's initialValue and we couldn't observe the transient flip.
        vm.uiState.test {
            awaitItem() // initial Loading
            assertEquals(false, vm.uiState.value.isRefreshing)

            // Gate refreshSubcategories so the launched refresh() coroutine suspends
            // mid-way. We can then assert isRefreshing == true while the gate holds.
            val gate = CompletableDeferred<Unit>()
            repo.suspendRefreshSubcategoriesUntil = gate

            vm.refresh()

            // While the repository is suspended the flag has flipped on. Drain
            // intermediate emissions until the indicator reaches true.
            val whileRefreshing = awaitContent { it.isRefreshing }
            assertEquals(true, whileRefreshing.isRefreshing)

            // Release the gate — refresh() resumes, runs refreshTopicList, returns,
            // and isRefreshing flips back off.
            gate.complete(Unit)
            val afterRefresh = awaitContent { !it.isRefreshing }
            assertEquals(false, afterRefresh.isRefreshing)
            assertTrue(repo.refreshTopicListCalls.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categoryName is preserved across Loading and Failure broadcasts`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            awaitItem() // initial — categoryName == null
            repo.emitCategories(
                ForumResult.Success(
                    listOf(
                        Category(id = 23, name = "Technologies Mobiles", forceSubcat = false, subcategoryCount = 5),
                    ),
                ),
            )
            val withName = awaitContent { it.categoryName == "Technologies Mobiles" }
            assertEquals("Technologies Mobiles", withName.categoryName)

            // A subsequent Loading (e.g. user pulled-to-refresh on Forum tab) must NOT
            // wipe the title back to "Catégorie 23". Same for a transient Failure.
            repo.emitCategories(ForumResult.Loading)
            assertEquals("Technologies Mobiles", vm.uiState.value.categoryName)

            repo.emitCategories(ForumResult.Failure(IllegalStateException("transient")))
            assertEquals("Technologies Mobiles", vm.uiState.value.categoryName)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchQuery filters by title author and lastReplyAuthor case- and accent-insensitively`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        val topics = listOf(
            topicSummary(id = 1, title = "Android et iOS", author = "alice", lastAuthor = "bob"),
            topicSummary(id = 2, title = "Réflexion sur la batterie", author = "Charlie", lastAuthor = "dave"),
            topicSummary(id = 3, title = "Câble USB-C", author = "Eve", lastAuthor = "Frédéric"),
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitSubcategories(ForumResult.Success(emptyList()))
            repo.emitTopicList(
                cat = 23,
                subcat = 550,
                page = 1,
                result = ForumResult.Success(EMPTY_PAGE.copy(topics = topics, totalTopics = topics.size)),
            )

            val withAll = awaitContent { it.topics is TopicsUiState.Content && it.filteredTopics.size == 3 }
            assertEquals(3, withAll.filteredTopics.size)

            // Title match — case-insensitive
            vm.updateSearchQuery("ANDROID")
            val byTitle = awaitContent { it.searchQuery == "ANDROID" && it.filteredTopics.size == 1 }
            assertEquals(setOf(1), byTitle.filteredTopics.map(TopicSummary::topicId).toSet())

            // Title match — accent-insensitive
            vm.updateSearchQuery("reflexion")
            val byAccent = awaitContent { it.searchQuery == "reflexion" && it.filteredTopics.size == 1 }
            assertEquals(setOf(2), byAccent.filteredTopics.map(TopicSummary::topicId).toSet())

            // Last reply author — accent-insensitive
            vm.updateSearchQuery("frederic")
            val byLastAuthor = awaitContent { it.searchQuery == "frederic" && it.filteredTopics.size == 1 }
            assertEquals(setOf(3), byLastAuthor.filteredTopics.map(TopicSummary::topicId).toSet())

            // Author match — case-insensitive substring
            vm.updateSearchQuery("CHAR")
            val byAuthor = awaitContent { it.searchQuery == "CHAR" && it.filteredTopics.size == 1 }
            assertEquals(setOf(2), byAuthor.filteredTopics.map(TopicSummary::topicId).toSet())

            // No match — empty list
            vm.updateSearchQuery("zzz")
            val noMatch = awaitContent { it.searchQuery == "zzz" }
            assertTrue(noMatch.filteredTopics.isEmpty())

            // Empty query — full list back
            vm.updateSearchQuery("")
            val cleared = awaitContent { it.searchQuery == "" && it.filteredTopics.size == 3 }
            assertEquals(3, cleared.filteredTopics.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchQuery is preserved across subcategory switches`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(
                cat = 23,
                subcat = null,
                page = 1,
                result = ForumResult.Success(EMPTY_PAGE.copy(subcat = null, totalTopics = 0)),
            )
            awaitContent { it.subcategories is SubcategoriesUiState.Content }

            vm.updateSearchQuery("hello")
            awaitContent { it.searchQuery == "hello" }

            vm.selectSubcategory(550)
            // After subcat switch the page resets to 1 — but the searchQuery stays.
            val afterSwitch = awaitContent { it.selectedSubcat == 550 }
            assertEquals("hello", afterSwitch.searchQuery)
            assertEquals(1, afterSwitch.page)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search is closed by default and openSearch activates it`() = runTest {
        val repo = FakeForumRepository()
        val vm = categoryVm(repo)

        vm.uiState.test {
            val initial = awaitContent { !it.searchActive }
            assertFalse("search must start closed", initial.searchActive)

            vm.openSearch()
            val opened = awaitContent { it.searchActive }
            assertTrue("openSearch must activate the search", opened.searchActive)
            // Opening does not seed a query — an open, empty field is a valid state.
            assertEquals("", opened.searchQuery)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing the query keeps the search open`() = runTest {
        // #1130 — the clear cross calls updateSearchQuery("") only; it must NOT leave the mode.
        val repo = FakeForumRepository()
        val vm = categoryVm(repo)

        vm.uiState.test {
            awaitContent { !it.searchActive }
            vm.openSearch()
            vm.updateSearchQuery("usb")
            awaitContent { it.searchActive && it.searchQuery == "usb" }

            vm.updateSearchQuery("")
            val cleared = awaitContent { it.searchQuery == "" }
            assertTrue("clearing the query must not close the search", cleared.searchActive)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closeSearch atomically empties and exits with no intermediate open-empty item`() = runTest {
        // #1130 — closeSearch writes ONE combined flow, so uiState must jump straight from
        // (open, "android") to (closed, "") with NO (open, "") item in between. The pre-fix
        // implementation wrote two separate MutableStateFlows joined by `combine` and DID surface
        // that intermediate; this test refutes it by draining every item emitted after closeSearch
        // and asserting none is the forbidden (active && empty) state before the atomic final one.
        val repo = FakeForumRepository()
        val vm = categoryVm(repo)

        vm.uiState.test {
            awaitContent { !it.searchActive }
            vm.openSearch()
            vm.updateSearchQuery("android")
            awaitContent { it.searchActive && it.searchQuery == "android" }

            vm.closeSearch()
            // Drain until the atomic final state (closed AND empty). Any item seen on the way that
            // is (open, empty) is the non-atomic leak the fix removes — it would arrive first.
            var item = awaitItem()
            while (item.searchActive || item.searchQuery.isNotEmpty()) {
                assertFalse(
                    "closeSearch leaked an intermediate (open, empty) state — not atomic",
                    item.searchActive && item.searchQuery.isEmpty(),
                )
                item = awaitItem()
            }
            assertFalse("closeSearch must leave the search mode", item.searchActive)
            assertEquals("closeSearch must empty the query", "", item.searchQuery)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failure from observeSubcategories surfaces as SubcategoriesUiState Error`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitSubcategories(ForumResult.Failure(IllegalStateException("subcats fail")))
            repo.emitTopicList(cat = 23, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            val current = awaitContent { it.subcategories is SubcategoriesUiState.Error }
            assertEquals(
                "subcats fail",
                (current.subcategories as SubcategoriesUiState.Error).message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Content emission triggers anonymous prefetch of the next page`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550, initialPage = 1),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )
        // Listings: 130 topics @ 50 per page → 3 pages total. Prefetch should fire for page 2.
        val multiPage = EMPTY_PAGE.copy(totalTopics = 130, resultsPerPage = 50)

        vm.uiState.test {
            awaitItem() // initial state
            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(cat = 23, subcat = 550, page = 1, result = ForumResult.Success(multiPage))
            awaitContent { it.topics is TopicsUiState.Content }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            "page 1 of a 3-page listing should fire one prefetch on page 2",
            listOf(Triple(23, 550, 2)),
            repo.prefetchTopicListCalls,
        )
    }

    @Test
    fun `prefetch is cancelled when the screen is left (issue #108)`() = runTest {
        // The prefetch coroutine lives on `viewModelScope`. Issue #108's acceptance
        // criterion is "le prefetch est annulé quand l'utilisateur quitte la page".
        // We simulate that by calling `ViewModel.clear()` (the API the framework
        // uses on screen leave) and verifying the in-flight prefetch sees a
        // [CancellationException].
        val cancelObserved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = FakeForumRepository().apply {
            prefetchHook = { _, _, _ ->
                try {
                    kotlinx.coroutines.awaitCancellation()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    cancelObserved.complete(Unit)
                    throw cancellation
                }
            }
        }
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550, initialPage = 1),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )
        val multiPage = EMPTY_PAGE.copy(totalTopics = 130, resultsPerPage = 50)

        vm.uiState.test {
            awaitItem()
            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(cat = 23, subcat = 550, page = 1, result = ForumResult.Success(multiPage))
            awaitContent { it.topics is TopicsUiState.Content }
            cancelAndIgnoreRemainingEvents()
        }
        // Prefetch must have launched and be suspended on awaitCancellation().
        assertEquals(listOf(Triple(23, 550, 2)), repo.prefetchTopicListCalls)

        // Simulate the screen leaving by routing the existing ViewModel through a
        // ViewModelStore. `ViewModelStore.clear()` is the public API the framework
        // uses on screen leave : it walks every stored ViewModel and triggers the
        // internal clear path that cancels `viewModelScope` and calls `onCleared()`.
        val store = androidx.lifecycle.ViewModelStore()
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        ).get(CategoryViewModel::class.java)
        store.clear()

        kotlinx.coroutines.withTimeout(AWAIT_CONTENT_TIMEOUT_MS) {
            cancelObserved.await()
        }
    }

    @Test
    fun `last page does not trigger a prefetch`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550, initialPage = 3),
            forumRepository = repo,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = FakePreferences(),
        )
        val multiPage = EMPTY_PAGE.copy(totalTopics = 130, resultsPerPage = 50, page = 3)

        vm.uiState.test {
            awaitItem()
            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(cat = 23, subcat = 550, page = 3, result = ForumResult.Success(multiPage))
            awaitContent { it.topics is TopicsUiState.Content }
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(
            "no prefetch on the final page",
            repo.prefetchTopicListCalls.isEmpty(),
        )
    }

    // ---- #455 — « Mes drapeaux » filter ----------------------------------------------

    @Test
    fun `flag filter defaults to ALL and shows the normal listing`() = runTest {
        val repo = FakeForumRepository()
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(pageOf(topicSummary(1, "Normal"))))
            val content = awaitContent { it.topics is TopicsUiState.Content }
            assertEquals(CategoryFlagFilter.ALL, content.flagFilter)
            assertEquals(listOf(1), content.filteredTopics.map { it.topicId })
            assertTrue("no bucket fetch in ALL mode", repo.getFlagFilteredTopicsCalls.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting a flag filter fetches the bucket and shows only its topics`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(99, "Flagged"))) }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(pageOf(topicSummary(1, "Normal"))))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            val filtered = awaitContent {
                it.flagFilter == CategoryFlagFilter.PARTICIPATED && it.flagFilterTopics is TopicsUiState.Content
            }
            assertEquals(listOf(99), filtered.filteredTopics.map { it.topicId })
            assertEquals(
                listOf(Triple(23, null, FlagFilterBucket.PARTICIPATED)),
                repo.getFlagFilteredTopicsCalls,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching back to ALL restores the normal listing`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(99, "Flagged"))) }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(pageOf(topicSummary(1, "Normal"))))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.FAVORITES)
            awaitContent {
                it.flagFilter == CategoryFlagFilter.FAVORITES && it.flagFilterTopics is TopicsUiState.Content
            }
            vm.selectFlagFilter(CategoryFlagFilter.ALL)
            val back = awaitContent { it.flagFilter == CategoryFlagFilter.ALL }
            assertEquals(listOf(1), back.filteredTopics.map { it.topicId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing subcat while a filter is active refetches the bucket for the new subcat`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, subcat, _ -> ForumResult.Success(pageOf(topicSummary(subcat ?: 0, "x"))) }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.READ)
            awaitContent { it.flagFilter == CategoryFlagFilter.READ && it.flagFilterTopics is TopicsUiState.Content }
            vm.selectSubcategory(550)
            awaitContent { it.selectedSubcat == 550 && it.flagFilterTopics is TopicsUiState.Content }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(Triple(23, null, FlagFilterBucket.READ), Triple(23, 550, FlagFilterBucket.READ)),
            repo.getFlagFilteredTopicsCalls,
        )
    }

    @Test
    fun `a failed bucket fetch surfaces an Error state`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Failure(RuntimeException("boom")) }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            val errored = awaitContent {
                it.flagFilter == CategoryFlagFilter.PARTICIPATED && it.flagFilterTopics is TopicsUiState.Error
            }
            assertTrue(errored.flagFilterTopics is TopicsUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reselecting the active filter does not refetch`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(EMPTY_PAGE) }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            awaitContent { it.flagFilterTopics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, repo.getFlagFilteredTopicsCalls.size)
    }

    @Test
    fun `refresh in flag-filter mode re-fetches the same bucket and brackets isRefreshing`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(7, "Flagged"))) }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            awaitContent { it.flagFilterTopics is TopicsUiState.Content }
            vm.refresh()
            awaitContent { !it.isRefreshing && it.flagFilterTopics is TopicsUiState.Content }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(
                Triple(23, null, FlagFilterBucket.PARTICIPATED),
                Triple(23, null, FlagFilterBucket.PARTICIPATED),
            ),
            repo.getFlagFilteredTopicsCalls,
        )
    }

    @Test
    fun `a filter change during a filtered refresh wins over the stale refresh result`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, bucket ->
            ForumResult.Success(pageOf(topicSummary(bucket.ordinal, bucket.name)))
        }
        val vm = categoryVm(repo)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            awaitContent {
                it.flagFilter == CategoryFlagFilter.PARTICIPATED && it.flagFilterTopics is TopicsUiState.Content
            }
            // Gate the refresh fetch so it stays in-flight while we switch filters.
            val gate = CompletableDeferred<Unit>()
            repo.suspendNextFlagFetchUntil = gate
            vm.refresh()
            // Switching filter must cancel the in-flight (gated) refresh fetch.
            vm.selectFlagFilter(CategoryFlagFilter.FAVORITES)
            // Release the (cancelled) refresh fetch: if the fix were absent it would resume and
            // clobber flagFilterTopics with the stale PARTICIPATED bucket.
            gate.complete(Unit)
            // Wait for the FAVORITES content specifically (id == FAVORITES.ordinal). A precise
            // predicate skips the transient (FAVORITES filter + old PARTICIPATED content) state
            // emitted right when the filter flips, before the new fetch posts Loading. If the
            // stale refresh had won, this would never reach [2] and time out.
            val favorites = awaitContent {
                it.flagFilter == CategoryFlagFilter.FAVORITES &&
                    it.filteredTopics.map { t -> t.topicId } == listOf(FlagFilterBucket.FAVORITES.ordinal)
            }
            assertEquals(
                listOf(FlagFilterBucket.FAVORITES.ordinal),
                favorites.filteredTopics.map { it.topicId },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `losing auth resets the flag filter to ALL`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(9, "Flagged"))) }
        val auth = FakeAuthRepository(initial = AuthState.Authenticated("xat"))
        val vm = categoryVm(repo, auth)
        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.canCreateTopic && it.topics is TopicsUiState.Content }
            vm.selectFlagFilter(CategoryFlagFilter.FAVORITES)
            awaitContent { it.flagFilter == CategoryFlagFilter.FAVORITES }
            auth.logout()
            val reset = awaitContent { it.flagFilter == CategoryFlagFilter.ALL }
            assertFalse("selector hidden once anonymous", reset.canCreateTopic)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- #1132 — remembering the last flag filter --------------------------------------------

    @Test
    fun `a persisted READ seed applies with a single fetch and never a visible ALL selector`() = runTest {
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(42, "Read"))) }
        val prefs = FakePreferences(initial = CategoryFlagFilter.READ)
        val auth = FakeAuthRepository(initial = AuthState.Authenticated("xat"))
        val vm = categoryVm(repo, auth, prefs = prefs)

        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            // The selector is only ever rendered while canCreateTopic is true. Drain to the seeded
            // READ state and assert NO authenticated item on the way surfaced the ALL selector — the
            // hydration gate must jump straight from the initial Loading (selector hidden) to READ.
            withTimeout(AWAIT_CONTENT_TIMEOUT_MS) {
                var item = awaitItem()
                while (!(item.canCreateTopic && item.flagFilter == CategoryFlagFilter.READ)) {
                    assertFalse(
                        "an authenticated state must never show the ALL selector before READ lands",
                        item.canCreateTopic && item.flagFilter == CategoryFlagFilter.ALL,
                    )
                    item = awaitItem()
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
        // Exactly one bucket fetch — the hydration fetch — despite the initial subcat emission (drop(1)).
        assertEquals(
            listOf(Triple(23, null, FlagFilterBucket.READ)),
            repo.getFlagFilteredTopicsCalls,
        )
    }

    @Test
    fun `a default ALL seed fetches no bucket`() = runTest {
        val repo = FakeForumRepository()
        val prefs = FakePreferences(initial = CategoryFlagFilter.ALL)
        val auth = FakeAuthRepository(initial = AuthState.Authenticated("xat"))
        val vm = categoryVm(repo, auth, prefs = prefs)

        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            val content = awaitContent { it.canCreateTopic && it.topics is TopicsUiState.Content }
            assertEquals(CategoryFlagFilter.ALL, content.flagFilter)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("an ALL seed must not fetch any bucket", repo.getFlagFilteredTopicsCalls.isEmpty())
    }

    @Test
    fun `selecting FAVORITES persists it and a new category ViewModel restores it as the seed`() = runTest {
        val prefs = FakePreferences(initial = CategoryFlagFilter.ALL)

        val repo1 = FakeForumRepository()
        repo1.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(9, "Fav"))) }
        val vm1 = categoryVm(repo1, FakeAuthRepository(initial = AuthState.Authenticated("xat")), prefs = prefs)
        vm1.uiState.test {
            repo1.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.canCreateTopic && it.flagFilter == CategoryFlagFilter.ALL }
            vm1.selectFlagFilter(CategoryFlagFilter.FAVORITES)
            awaitContent { it.flagFilter == CategoryFlagFilter.FAVORITES }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(CategoryFlagFilter.FAVORITES), prefs.setCalls)

        // A brand-new category ViewModel sharing the same (persisted) preferences seeds FAVORITES.
        val repo2 = FakeForumRepository()
        repo2.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(9, "Fav"))) }
        val vm2 = categoryVm(repo2, FakeAuthRepository(initial = AuthState.Authenticated("xat")), prefs = prefs)
        vm2.uiState.test {
            repo2.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            val seeded = awaitContent { it.canCreateTopic && it.flagFilter == CategoryFlagFilter.FAVORITES }
            assertEquals(CategoryFlagFilter.FAVORITES, seeded.flagFilter)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(Triple(23, null, FlagFilterBucket.FAVORITES)),
            repo2.getFlagFilteredTopicsCalls,
        )
    }

    @Test
    fun `logout resets the local filter to ALL but leaves the persisted preference untouched`() = runTest {
        val prefs = FakePreferences(initial = CategoryFlagFilter.ALL)
        val auth = FakeAuthRepository(initial = AuthState.Authenticated("xat"))
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(9, "Fav"))) }
        val vm = categoryVm(repo, auth, prefs = prefs)

        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.canCreateTopic }
            vm.selectFlagFilter(CategoryFlagFilter.FAVORITES)
            awaitContent { it.flagFilter == CategoryFlagFilter.FAVORITES }
            auth.logout()
            val reset = awaitContent { it.flagFilter == CategoryFlagFilter.ALL }
            assertFalse("selector hidden once anonymous", reset.canCreateTopic)
            cancelAndIgnoreRemainingEvents()
        }
        // The only write was the explicit FAVORITES selection; the logout reset must NOT have written.
        assertEquals(listOf(CategoryFlagFilter.FAVORITES), prefs.setCalls)
    }

    @Test
    fun `an anonymous entry with FAVORITES stored seeds local ALL with no bucket fetch and no write`() = runTest {
        val prefs = FakePreferences(initial = CategoryFlagFilter.FAVORITES)
        val auth = FakeAuthRepository(initial = AuthState.Anonymous)
        val repo = FakeForumRepository()
        val vm = categoryVm(repo, auth, prefs = prefs)

        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            val content = awaitContent { it.topics is TopicsUiState.Content }
            assertEquals(CategoryFlagFilter.ALL, content.flagFilter)
            assertFalse("an anonymous session hides the selector", content.canCreateTopic)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("an anonymous seed must not fetch a bucket", repo.getFlagFilteredTopicsCalls.isEmpty())
        assertTrue("an anonymous seed must not write the preference", prefs.setCalls.isEmpty())
    }

    @Test
    fun `reselecting the already-active filter neither refetches nor rewrites the preference`() = runTest {
        val prefs = FakePreferences(initial = CategoryFlagFilter.ALL)
        val auth = FakeAuthRepository(initial = AuthState.Authenticated("xat"))
        val repo = FakeForumRepository()
        repo.flagFilterResponder = { _, _, _ -> ForumResult.Success(pageOf(topicSummary(9, "Fav"))) }
        val vm = categoryVm(repo, auth, prefs = prefs)

        vm.uiState.test {
            repo.emitTopicList(23, null, 1, ForumResult.Success(EMPTY_PAGE))
            awaitContent { it.canCreateTopic }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED)
            awaitContent {
                it.flagFilter == CategoryFlagFilter.PARTICIPATED && it.flagFilterTopics is TopicsUiState.Content
            }
            vm.selectFlagFilter(CategoryFlagFilter.PARTICIPATED) // identical — must be a no-op
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, repo.getFlagFilteredTopicsCalls.size)
        assertEquals(listOf(CategoryFlagFilter.PARTICIPATED), prefs.setCalls)
    }

    @Test
    fun `layout defaults are expanded and all four choices restore in another anonymous category`() = runTest {
        val prefs = FakePreferences()
        val first = categoryVm(FakeForumRepository(), prefs = prefs)
        first.uiState.test {
            val initial = awaitContent { it.layoutPreferencesReady }
            assertFalse(initial.menusCollapsed)
            assertFalse(initial.stickyTopicsCollapsed)
            cancelAndIgnoreRemainingEvents()
        }
        for (menus in listOf(false, true)) {
            for (sticky in listOf(false, true)) {
                first.setMenusCollapsed(menus)
                first.setStickyTopicsCollapsed(sticky)
                val next = categoryVm(
                    FakeForumRepository(),
                    request = CategoryRequest(cat = 13, initialSubcat = null),
                    prefs = prefs,
                )
                next.uiState.test {
                    val restored = awaitContent { it.layoutPreferencesReady }
                    assertEquals(menus, restored.menusCollapsed)
                    assertEquals(sticky, restored.stickyTopicsCollapsed)
                    assertFalse(restored.canCreateTopic)
                    cancelAndIgnoreRemainingEvents()
                }
                clearVm(next)
            }
        }
        clearVm(first)
    }

    @Test
    fun `layout remains unready until both delayed preferences arrive without expanded flash`() = runTest {
        val menusGate = CompletableDeferred<Unit>()
        val stickyGate = CompletableDeferred<Unit>()
        val prefs = FakePreferences(
            initialMenus = true, initialSticky = true, menusGate = menusGate, stickyGate = stickyGate,
        )
        val vm = categoryVm(FakeForumRepository(), prefs = prefs)
        vm.uiState.test {
            assertFalse(awaitItem().layoutPreferencesReady)
            vm.setMenusCollapsed(false)
            vm.setStickyTopicsCollapsed(false)
            assertTrue(prefs.menuWrites.isEmpty())
            assertTrue(prefs.stickyWrites.isEmpty())
            menusGate.complete(Unit)
            val partial = awaitContent { it.menusCollapsed }
            assertFalse(partial.layoutPreferencesReady)
            stickyGate.complete(Unit)
            val ready = awaitContent { it.layoutPreferencesReady }
            assertTrue(ready.menusCollapsed)
            assertTrue(ready.stickyTopicsCollapsed)
            cancelAndIgnoreRemainingEvents()
        }
        clearVm(vm)
    }

    @Test
    fun `rapid layout taps skip unchanged values and search never writes preferences`() = runTest {
        val prefs = FakePreferences()
        val vm = categoryVm(FakeForumRepository(), prefs = prefs)
        vm.uiState.test {
            awaitContent { it.layoutPreferencesReady }
            vm.setMenusCollapsed(false)
            vm.setStickyTopicsCollapsed(false)
            for (collapsed in listOf(true, true, false, true)) vm.setMenusCollapsed(collapsed)
            for (collapsed in listOf(true, true, false)) vm.setStickyTopicsCollapsed(collapsed)
            vm.openSearch()
            vm.updateSearchQuery("Android")
            vm.closeSearch()
            val closed = awaitContent { it.menusCollapsed && !it.searchActive && it.searchQuery.isEmpty() }
            assertFalse(closed.stickyTopicsCollapsed)
            assertEquals(listOf(true, false, true), prefs.menuWrites)
            assertEquals(listOf(true, false), prefs.stickyWrites)
            assertTrue(prefs.setCalls.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        clearVm(vm)
    }

    @Test
    fun `layout changes preserve page subcategory query and all fetch counts`() = runTest {
        val repo = FakeForumRepository()
        val vm = categoryVm(
            repo,
            request = CategoryRequest(cat = 23, initialSubcat = 550, initialPage = 2),
        )
        vm.uiState.test {
            awaitContent { it.layoutPreferencesReady }
            repo.emitTopicList(23, 550, 2, ForumResult.Success(EMPTY_PAGE.copy(page = 2)))
            vm.openSearch()
            vm.updateSearchQuery("Android")
            awaitContent { it.searchQuery == "Android" && it.topics is TopicsUiState.Content }
            val observed = repo.observeTopicListCalls.toList()
            val prefetched = repo.prefetchTopicListCalls.toList()
            vm.setMenusCollapsed(true)
            vm.setStickyTopicsCollapsed(true)
            val collapsed = awaitContent { it.menusCollapsed && it.stickyTopicsCollapsed }
            assertEquals(2, collapsed.page)
            assertEquals(550, collapsed.selectedSubcat)
            assertEquals("Android", collapsed.searchQuery)
            assertTrue(collapsed.searchActive)
            assertEquals(observed, repo.observeTopicListCalls)
            assertEquals(prefetched, repo.prefetchTopicListCalls)
            assertTrue(repo.refreshTopicListCalls.isEmpty())
            assertTrue(repo.refreshSubcategoriesCalls.isEmpty())
            assertTrue(repo.getFlagFilteredTopicsCalls.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        clearVm(vm)
    }

    @Test
    fun `layout setter reaches shared cache before immediate ViewModel cancellation`() = runTest {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val prefs = FakePreferences()
        val vm = categoryVm(FakeForumRepository(), prefs = prefs)
        vm.uiState.test {
            awaitContent { it.layoutPreferencesReady }
            vm.setMenusCollapsed(true)
            vm.setStickyTopicsCollapsed(true)
            // No scheduler turn between the actions and route removal.
            clearVm(vm)
            assertEquals(listOf(true), prefs.menuWrites)
            assertEquals(listOf(true), prefs.stickyWrites)
            cancelAndIgnoreRemainingEvents()
        }
        val next = categoryVm(FakeForumRepository(), prefs = prefs)
        next.uiState.test {
            val ready = awaitContent { it.layoutPreferencesReady }
            assertTrue(ready.menusCollapsed)
            assertTrue(ready.stickyTopicsCollapsed)
            cancelAndIgnoreRemainingEvents()
        }
        clearVm(next)
    }

    @Test
    fun `failed layout writes keep the session choice without an uncaught exception`() = runTest {
        val prefs = FakePreferences().apply { layoutWriteFailure = java.io.IOException("Disk full") }
        val vm = categoryVm(FakeForumRepository(), prefs = prefs)
        vm.uiState.test {
            awaitContent { it.layoutPreferencesReady }
            vm.setMenusCollapsed(true)
            vm.setStickyTopicsCollapsed(true)
            val chosen = awaitContent { it.menusCollapsed && it.stickyTopicsCollapsed }
            assertTrue(chosen.layoutPreferencesReady)
            cancelAndIgnoreRemainingEvents()
        }
        val next = categoryVm(FakeForumRepository(), prefs = prefs)
        next.uiState.test {
            val restored = awaitContent { it.layoutPreferencesReady }
            assertTrue(restored.menusCollapsed)
            assertTrue(restored.stickyTopicsCollapsed)
            cancelAndIgnoreRemainingEvents()
        }
        clearVm(vm)
        clearVm(next)
    }

    private fun clearVm(vm: CategoryViewModel) {
        val store = androidx.lifecycle.ViewModelStore()
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        ).get(CategoryViewModel::class.java)
        store.clear()
    }

    private fun categoryVm(
        repo: FakeForumRepository,
        auth: AuthRepository = FakeAuthRepository(),
        request: CategoryRequest = CategoryRequest(cat = 23, initialSubcat = null),
        prefs: UserPreferencesRepository = FakePreferences(),
    ): CategoryViewModel = CategoryViewModel(
        request = request,
        forumRepository = repo,
        authRepository = auth,
        userPreferencesRepository = prefs,
    )

    private companion object {
        val SUBCAT_550 = SubCategory(id = 550, name = "Android", parentCategoryId = 23)
        val EMPTY_PAGE = TopicListPage(
            cat = 23,
            subcat = 550,
            page = 1,
            resultsPerPage = 50,
            totalTopics = 0,
            topics = emptyList<TopicSummary>(),
        )
        const val AWAIT_CONTENT_TIMEOUT_MS = 2_000L

        fun pageOf(vararg topics: TopicSummary): TopicListPage = TopicListPage(
            cat = 23,
            subcat = null,
            page = 1,
            resultsPerPage = 100,
            totalTopics = topics.size,
            topics = topics.toList(),
        )

        fun topicSummary(
            id: Int,
            title: String,
            author: String = "anon",
            lastAuthor: String = author,
        ): TopicSummary = TopicSummary(
            cat = 23,
            subcat = 550,
            topicId = id,
            title = title,
            author = author,
            lastReplyAuthor = lastAuthor,
            lastReplyAt = "2026-05-02 10:00",
            replyCount = 0,
            totalPages = 1,
            isSticky = false,
            isLocked = false,
            hasUnread = null,
            lastReadPage = null,
            lastPostReadId = null,
            flagType = null,
        )
    }

    /**
     * Bounded helper around `awaitItem()` to drain warm-up emissions until the predicate
     * holds. Replaces the unbounded `do { ... } while (true)` loops that risked hanging
     * the test runner if the predicate was never satisfied.
     */
    private suspend fun app.cash.turbine.ReceiveTurbine<CategoryUiState>.awaitContent(
        predicate: (CategoryUiState) -> Boolean,
    ): CategoryUiState = withTimeout(AWAIT_CONTENT_TIMEOUT_MS) {
        var current = awaitItem()
        while (!predicate(current)) {
            current = awaitItem()
        }
        current
    }

    private class FakeForumRepository : ForumRepository {
        private val categories: MutableSharedFlow<ForumResult<List<Category>>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        private val subcategories: MutableSharedFlow<ForumResult<List<SubCategory>>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        private val topicLists: MutableMap<
            Triple<Int, Int?, Int>,
            MutableSharedFlow<ForumResult<TopicListPage>>,
            > = mutableMapOf()

        var refreshSubcategoriesCalls: List<Int> = emptyList()
            private set
        var refreshTopicListCalls: List<Triple<Int, Int?, Int>> = emptyList()
            private set
        var prefetchTopicListCalls: List<Triple<Int, Int?, Int>> = emptyList()
            private set

        // #455 — flag-filter fetch. [flagFilterResponder] lets a test return Success/Failure
        // per (cat, subcat, bucket); [getFlagFilteredTopicsCalls] records the args so a test
        // can assert the right bucket / subcat were requested. Default = empty Success page.
        var flagFilterResponder: (Int, Int?, FlagFilterBucket) -> ForumResult<TopicListPage> =
            { cat, subcat, _ -> ForumResult.Success(TopicListPage(cat, subcat, 1, 100, 0, emptyList())) }
        var getFlagFilteredTopicsCalls: List<Triple<Int, Int?, FlagFilterBucket>> = emptyList()
            private set

        /**
         * Optional gate consumed by [refreshSubcategories] — when set, the suspending
         * stub awaits the gate before recording the call and returning. Lets a test
         * pin the in-flight state of [CategoryViewModel.isRefreshing].
         */
        var suspendRefreshSubcategoriesUntil: CompletableDeferred<Unit>? = null

        override fun observeCategories(): Flow<ForumResult<List<Category>>> =
            categories.asSharedFlow()

        override suspend fun getCategories(forceRefreshIfStale: Boolean): ForumResult<List<Category>> =
            categories.replayCache.lastOrNull { it !is ForumResult.Loading }
                ?: ForumResult.Success(emptyList())

        override suspend fun refreshCategories() = Unit

        override fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>> =
            subcategories.asSharedFlow()

        override fun observeCachedSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>?> =
            subcategories.asSharedFlow()

        override suspend fun refreshSubcategories(cat: Int) {
            suspendRefreshSubcategoriesUntil?.await()
            refreshSubcategoriesCalls = refreshSubcategoriesCalls + cat
        }

        val observeTopicListCalls = mutableListOf<Triple<Int, Int?, Int>>()

        override fun observeTopicList(
            cat: Int,
            subcat: Int?,
            page: Int,
        ): Flow<ForumResult<TopicListPage>> {
            observeTopicListCalls += Triple(cat, subcat, page)
            return topicListFlow(cat, subcat, page).asSharedFlow()
        }

        override suspend fun refreshTopicList(cat: Int, subcat: Int?, page: Int) {
            refreshTopicListCalls = refreshTopicListCalls + Triple(cat, subcat, page)
        }

        /**
         * Optional hook that lets a test gate the prefetch coroutine — e.g. suspend
         * forever to verify cancellation propagates from a parent scope cancel.
         */
        var prefetchHook: (suspend (cat: Int, subcat: Int?, page: Int) -> Unit)? = null

        override suspend fun prefetchTopicList(cat: Int, subcat: Int?, page: Int) {
            prefetchTopicListCalls = prefetchTopicListCalls + Triple(cat, subcat, page)
            prefetchHook?.invoke(cat, subcat, page)
        }

        /**
         * When set, the NEXT [getFlagFilteredTopics] call awaits this gate before returning,
         * letting a test pin an in-flight bucket fetch (e.g. a gated refresh) while another
         * action runs. Consumed (reset to null) on first use. The await happens on the
         * ViewModel's Main dispatcher (no separate scheduler), so cancellation/completion
         * resolve eagerly under [UnconfinedTestDispatcher] — no risk of a hung test.
         */
        var suspendNextFlagFetchUntil: CompletableDeferred<Unit>? = null

        override suspend fun getFlagFilteredTopics(
            cat: Int,
            subcat: Int?,
            bucket: FlagFilterBucket,
        ): ForumResult<TopicListPage> {
            getFlagFilteredTopicsCalls = getFlagFilteredTopicsCalls + Triple(cat, subcat, bucket)
            suspendNextFlagFetchUntil?.let { gate ->
                suspendNextFlagFetchUntil = null
                gate.await()
            }
            return flagFilterResponder(cat, subcat, bucket)
        }

        suspend fun emitCategories(result: ForumResult<List<Category>>) {
            categories.emit(result)
        }

        suspend fun emitSubcategories(result: ForumResult<List<SubCategory>>) {
            subcategories.emit(result)
        }

        suspend fun emitTopicList(
            cat: Int,
            subcat: Int?,
            page: Int,
            result: ForumResult<TopicListPage>,
        ) {
            topicListFlow(cat, subcat, page).emit(result)
        }

        private fun topicListFlow(
            cat: Int,
            subcat: Int?,
            page: Int,
        ): MutableSharedFlow<ForumResult<TopicListPage>> = topicLists.getOrPut(Triple(cat, subcat, page)) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        }
    }

    /**
     * Minimal fake. Phase 2E (#149) `CategoryViewModel` only reads
     * `observeAuthState()` to drive `canCreateTopic`. Default `Anonymous` keeps
     * the existing tests faithful to their pre-#149 behaviour (FAB hidden).
     */
    private class FakeAuthRepository(
        initial: AuthState = AuthState.Anonymous,
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)
        override fun observeAuthState(): Flow<AuthState> = state
        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            error("FakeAuthRepository.login not implemented in this test")
        override suspend fun logout() {
            state.value = AuthState.Anonymous
        }
    }

    /**
     * #1132 — records / stores the persisted Forum flag-filter so a test can assert the write AND a
     * SECOND ViewModel can restore it. Everything else on [UserPreferencesRepository] is delegated to
     * a relaxed mock: the ViewModel only touches the Forum preferences, so this avoids a
     * hand-written stub of the ~85 other preference accessors.
     */
    private class FakePreferences(
        initial: CategoryFlagFilter = CategoryFlagFilter.ALL,
        initialMenus: Boolean = false,
        initialSticky: Boolean = false,
        val menusGate: CompletableDeferred<Unit>? = null,
        val stickyGate: CompletableDeferred<Unit>? = null,
    ) : UserPreferencesRepository by mockk(relaxed = true) {
        private val stored = MutableStateFlow(initial)
        var setCalls: List<CategoryFlagFilter> = emptyList()
            private set

        private val menusCollapsed = MutableStateFlow(initialMenus)
        private val stickyCollapsed = MutableStateFlow(initialSticky)

        var layoutWriteFailure: java.io.IOException? = null
        val menuWrites = mutableListOf<Boolean>()
        val stickyWrites = mutableListOf<Boolean>()

        override fun observeForumCategoryMenusCollapsed(): Flow<Boolean> = flow {
            menusGate?.await()
            emitAll(menusCollapsed)
        }
        override suspend fun setForumCategoryMenusCollapsed(collapsed: Boolean) {
            menuWrites += collapsed
            menusCollapsed.value = collapsed
            layoutWriteFailure?.let { throw it }
        }

        override fun observeForumCategoryStickyTopicsCollapsed(): Flow<Boolean> = flow {
            stickyGate?.await()
            emitAll(stickyCollapsed)
        }
        override suspend fun setForumCategoryStickyTopicsCollapsed(collapsed: Boolean) {
            stickyWrites += collapsed
            stickyCollapsed.value = collapsed
            layoutWriteFailure?.let { throw it }
        }

        override fun observeForumCategoryFlagFilter(): Flow<CategoryFlagFilter> = stored

        override suspend fun setForumCategoryFlagFilter(filter: CategoryFlagFilter) {
            setCalls = setCalls + filter
            stored.value = filter
        }

        override fun observeTopicUnansweredPollsExpanded(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicUnansweredPollsExpanded(enabled: Boolean) = Unit
    }
}
