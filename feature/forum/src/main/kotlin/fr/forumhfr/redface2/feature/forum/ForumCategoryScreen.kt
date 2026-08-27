package fr.forumhfr.redface2.feature.forum

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicSummary
import fr.forumhfr.redface2.core.ui.FlagMetadata
import fr.forumhfr.redface2.core.ui.TopicMetadataLine
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.formatLastReplyTimestamp
import fr.forumhfr.redface2.core.ui.theme.FlagPalette
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics

/**
 * Per-category screen: chip row of subcategories ("Toutes" + each subcat) on top, list
 * of [TopicSummary] below, with a basic "previous / next page" pager. Tapping a topic
 * fires [onOpenTopic] with the right `(cat, post, page, scrollTo)` triple — when the
 * authenticated payload exposes `lastPostReadId`, the user lands directly on their last
 * read position; otherwise [onOpenTopic] is called with `page = 1` and no scroll target.
 *
 * Phase 1C-B additions:
 * - Material 3 [PullToRefreshBox] anchored over the topic body.
 * - Local in-page search field, filtering on title / author / last reply author.
 * - Per-row flag badge driven by the auth-only `flag_owntopic` REST field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumCategoryScreen(
    request: CategoryRequest,
    onOpenTopic: (TopicSummary) -> Unit,
    onCreateTopic: (cat: Int, subcat: Int?) -> Unit,
) {
    val viewModel = hiltViewModel<CategoryViewModel, CategoryViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // #482 — hoisted so the FAB can collapse to icon-only once the listing is scrolled
    // (Azgor: the extended FAB is "presque envahissant"). Shared with the LazyColumn below.
    val listState = rememberLazyListState()
    // Expanded only while resting at the very top; any scroll shrinks it out of the way.
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // #1130 — system/gesture back leaves the search mode first (clearing the query) instead of
    // popping the category route, mirroring the settings search shell. Disabled when the search
    // is closed so normal back navigation proceeds. The active state lives in the ViewModel so
    // the close/clear logic stays unit-testable without a Compose harness.
    BackHandler(enabled = state.searchActive) { viewModel.closeSearch() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            if (state.canCreateTopic) {
                ExtendedFloatingActionButton(
                    onClick = { onCreateTopic(state.cat, state.selectedSubcat) },
                    expanded = fabExpanded,
                    text = { Text(text = stringResource(R.string.category_create_topic)) },
                    icon = { Text(text = "+") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Text(
                // Real category name when known (e.g. "Technologies Mobiles"), fall back
                // to "Catégorie <id>" while categories are still loading or unreachable.
                text = state.categoryName
                    ?: stringResource(R.string.category_title_fallback, state.cat),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            SubcategoryChips(
                state = state.subcategories,
                selectedSubcat = state.selectedSubcat,
                onSelect = viewModel::selectSubcategory,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // #455 — « Mes drapeaux » filter, mirroring the web `owntopic` toolbar. Only an
            // authenticated session has flags, so the selector is hidden for anonymous users.
            if (state.canCreateTopic) {
                FlagFilterSelector(
                    selected = state.flagFilter,
                    onSelect = viewModel::selectFlagFilter,
                )
            }

            SearchField(
                searchActive = state.searchActive,
                query = state.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                onOpenSearch = viewModel::openSearch,
                onCloseSearch = viewModel::closeSearch,
            )

            // In flag-filter mode the bucket listing is the source (and the pager is hidden,
            // buckets are not paginated); ALL keeps the normal paginated listing.
            val filterActive = state.flagFilter != CategoryFlagFilter.ALL
            val activeTopics = if (filterActive) state.flagFilterTopics else state.topics
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                TopicsBody(
                    state = activeTopics,
                    listState = listState,
                    filteredTopics = state.filteredTopics,
                    searchQuery = state.searchQuery,
                    onOpenTopic = onOpenTopic,
                    onRetry = viewModel::refresh,
                    onSelectPage = viewModel::selectPage,
                    currentPage = state.page,
                    pageCount = state.pageCount,
                    showPager = !filterActive,
                    // #1129 — the sticky partition + « Autres sujets » separator only apply to a
                    // real category listing; flag-filter buckets stay flat (see TopicsBody).
                    filterActive = filterActive,
                    // #1131 — reserve the FAB clearance so the pager clears the « + » button.
                    // The FAB is rendered only when the user can create a topic.
                    contentPadding = forumListContentPadding(reserveFabSpace = state.canCreateTopic),
                    // #206 workaround — highlight only on the listing page/subcat reached
                    // immediately after create. If the user changes page or subcat, the route
                    // hint is ignored so an unrelated same-title topic is not highlighted there.
                    highlightTitle = routeScopedHighlightTitle(
                        request = request,
                        selectedSubcat = state.selectedSubcat,
                        page = state.page,
                    ),
                )
            }
        }
    }
}

/**
 * #1130 — the in-page search affordance with an explicit open/closed mode, mirroring the
 * settings search shell (`RedfaceSettingsSearchTopBar` / `RedfaceSearchAppBar`).
 *
 * - Closed ([searchActive] false): a full-width pill inviting the user to search. Tapping it
 *   opens the search.
 * - Open ([searchActive] true): an [OutlinedTextField] with a back arrow (leading) that closes
 *   the search, a clear cross (trailing, only while the query is non-empty) that empties the
 *   query WITHOUT leaving the mode, autofocus, and an `ImeAction.Search` that just dismisses
 *   the keyboard (filtering is live).
 *
 * Opening is not derived from a non-empty query — an open, empty field is a valid state.
 */
@Composable
private fun SearchField(
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    if (searchActive) {
        ActiveSearchField(
            query = query,
            onQueryChange = onQueryChange,
            onCloseSearch = onCloseSearch,
        )
    } else {
        SearchPill(onOpenSearch = onOpenSearch)
    }
}

/** Closed-state affordance: a pill that opens the search, styled like the settings search bar. */
@Composable
private fun SearchPill(onOpenSearch: () -> Unit) {
    val openLabel = stringResource(R.string.category_search_open)
    Surface(
        onClick = onOpenSearch,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(56.dp)
            .semantics { contentDescription = openLabel },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.category_search_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Open-state field: back arrow closes, clear cross empties the query, autofocus + IME Search. */
@Composable
private fun ActiveSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // Auto-focus + open the keyboard as soon as the field enters composition (i.e. when the
    // search is activated): without this the field shows but stays unfocused.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        label = { Text(stringResource(R.string.category_search_label)) },
        placeholder = { Text(stringResource(R.string.category_search_placeholder)) },
        leadingIcon = {
            val closeLabel = stringResource(R.string.category_search_close)
            IconButton(
                onClick = onCloseSearch,
                modifier = Modifier.semantics { contentDescription = closeLabel },
            ) {
                Icon(
                    painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                    contentDescription = null,
                )
            }
        },
        trailingIcon = {
            // Clear is offered only when there is something to clear; it empties the query but
            // leaves the search open (an open, empty field is a valid state).
            if (query.isNotEmpty()) {
                val clearLabel = stringResource(R.string.category_search_clear)
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = clearLabel },
                ) {
                    Icon(
                        painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_close),
                        contentDescription = null,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Filtering is live; the IME "Search" action just dismisses the keyboard (no close).
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

/**
 * #455 — single-choice segmented row replicating the web `owntopic` toolbar: Tous /
 * Participé / Lus / Favoris. No Material icon (detekt `ForbiddenImport` bans
 * `androidx.compose.material.*`), labels only.
 */
@Composable
private fun FlagFilterSelector(
    selected: CategoryFlagFilter,
    onSelect: (CategoryFlagFilter) -> Unit,
) {
    val options = listOf(
        CategoryFlagFilter.ALL to R.string.category_flag_filter_all,
        CategoryFlagFilter.PARTICIPATED to R.string.category_flag_filter_participated,
        CategoryFlagFilter.READ to R.string.category_flag_filter_read,
        CategoryFlagFilter.FAVORITES to R.string.category_flag_filter_favorites,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, (mode, labelRes) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(text = stringResource(labelRes), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SubcategoryChips(
    state: SubcategoriesUiState,
    selectedSubcat: Int?,
    onSelect: (Int?) -> Unit,
) {
    when (state) {
        SubcategoriesUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CircularProgressIndicator()
            }
        }

        is SubcategoriesUiState.Error -> {
            Text(
                // #324 — ServerDown / Network render the shared :core:ui label; Other
                // keeps the pre-existing rendering (raw message, generic fallback).
                text = state.kind.sharedLabelResOrNull()?.let { stringResource(it) }
                    ?: state.message
                    ?: stringResource(R.string.category_subcategories_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        is SubcategoriesUiState.Content -> {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedSubcat == null,
                        onClick = { onSelect(null) },
                        label = { Text(stringResource(R.string.category_filter_all)) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
                items(state.subcategories, key = SubCategory::id) { subcategory ->
                    FilterChip(
                        selected = selectedSubcat == subcategory.id,
                        onClick = { onSelect(subcategory.id) },
                        label = { Text(subcategory.name) },
                    )
                }
            }
        }
    }
}

/**
 * Compose composables idiomatically take many small focused params (state slice +
 * callbacks + a pager position). 8 args here is below most Material 3 composable
 * signatures' real-world threshold, but detekt's default `functionThreshold = 6`
 * fires on equality. Suppressing locally rather than relaxing the project rule.
 */
@Suppress("LongParameterList")
@Composable
private fun TopicsBody(
    state: TopicsUiState,
    listState: LazyListState,
    filteredTopics: List<TopicSummary>,
    searchQuery: String,
    onOpenTopic: (TopicSummary) -> Unit,
    onRetry: () -> Unit,
    onSelectPage: (Int) -> Unit,
    currentPage: Int,
    pageCount: Int,
    showPager: Boolean,
    filterActive: Boolean,
    contentPadding: PaddingValues,
    highlightTitle: String?,
) {
    when (state) {
        TopicsUiState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is TopicsUiState.Error -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.category_topics_error),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            // #324 — ServerDown / Network swap the raw exception message for the shared
            // :core:ui label; Other keeps the pre-existing rendering (raw message if any).
            val detail = state.kind.sharedLabelResOrNull()?.let { stringResource(it) }
                ?: state.message
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.forum_action_retry))
            }
        }

        is TopicsUiState.Content -> {
            // The PagerRow always shows the underlying `state.page` total — a search
            // filter only narrows the visible rows in the current page; switching
            // page or subcat is what actually re-fetches.
            val sections = remember(filteredTopics) { filteredTopics.toTopicSections() }
            // #1129 — the sticky partition + « Autres sujets » separator only make sense on a
            // real, recency-agnostic category listing. Flag-filter buckets (Participé/Lus/
            // Favoris) are recency-sorted cross-category views, so a pinned topic must NOT jump
            // to the top with a separator there: in filter mode the list stays flat, exactly as
            // before #1129. The per-row status badge still shows in both modes (it is in TopicRow).
            val showStickyBoundary = sections.shouldShowStickyBoundary(filterActive)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = contentPadding,
            ) {
                if (filteredTopics.isEmpty()) {
                    item { TopicsEmpty(searchQuery = searchQuery) }
                } else if (filterActive) {
                    items(filteredTopics, key = TopicSummary::topicId) { topic ->
                        TopicRow(
                            topic = topic,
                            highlighted = matchesHighlightedTitle(topic, highlightTitle),
                            onClick = { onOpenTopic(topic) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else {
                    itemsIndexed(
                        items = sections.sticky,
                        key = { _, topic -> topic.topicId },
                    ) { index, topic ->
                        TopicRow(
                            topic = topic,
                            highlighted = matchesHighlightedTitle(topic, highlightTitle),
                            onClick = { onOpenTopic(topic) },
                        )
                        // The boundary owns its two 2.dp rules, so the last sticky row must not
                        // add the ordinary hairline immediately before it.
                        if (index < sections.sticky.lastIndex || !showStickyBoundary) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (showStickyBoundary) {
                        item(key = "sticky_boundary") {
                            StickyTopicsSeparator()
                        }
                    }
                    items(sections.regular, key = TopicSummary::topicId) { topic ->
                        TopicRow(
                            topic = topic,
                            highlighted = matchesHighlightedTitle(topic, highlightTitle),
                            onClick = { onOpenTopic(topic) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (showPager) {
                    item {
                        PagerRow(
                            currentPage = currentPage,
                            pageCount = pageCount,
                            onSelectPage = onSelectPage,
                        )
                    }
                }
            }
        }
    }
}

/**
 * #1129 — boundary between sticky topics and the regular topics of the loaded, filtered page.
 * Mirrors the Topic screen's « Dernier message lu » grammar: traversing primary rules around a
 * compact central pill.
 */
@Composable
private fun StickyTopicsSeparator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ruleColor = MaterialTheme.colorScheme.primary.copy(alpha = STICKY_TOPICS_RULE_ALPHA)
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 2.dp,
            color = ruleColor,
        )
        Text(
            text = stringResource(R.string.category_regular_topics_separator),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 2.dp,
            color = ruleColor,
        )
    }
}

private const val STICKY_TOPICS_RULE_ALPHA = 0.55f

@Composable
private fun TopicsEmpty(searchQuery: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (searchQuery.isBlank()) {
                stringResource(R.string.category_topics_empty)
            } else {
                stringResource(R.string.category_topics_empty_search, searchQuery)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopicRow(
    topic: TopicSummary,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    // #206 workaround — tint the freshly-created topic's row with the same M3 role the
    // topic reader uses to highlight a target post (`secondaryContainer`, cf.
    // `TopicScreen.TopicPostCard`), so the surbrillance is sober and consistent across
    // the app. No hard-coded colour. `highlighted` is false on every normal nav path and
    // after page/subcat changes, so the row keeps the default transparent background then.
    // NB : this is NOT a transient flash — it stays while the landing page/subcat is shown.
    // Exact duplicate titles remain the unavoidable ambiguity because HFR exposes no id on
    // create.
    val newTopicHighlightDescription = stringResource(R.string.category_topic_new_highlight)
    // When highlighted, pair the text with `onSecondaryContainer` so the contrast is the
    // one M3 guarantees against `secondaryContainer` (plain `onSurface` is not a guaranteed
    // pairing, notably in dark theme). Also expose a `stateDescription` so the highlight is
    // not a colour-only signal (TalkBack / colour-blind), mirroring `FlagIndicator` below.
    val titleColor = if (highlighted) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metadataColor = if (highlighted) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.74f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // #287 — listing-row vertical rhythm from the density preset (Comfort = 10 dp, the lot A value).
    val m = LocalDisplayMetrics.current
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (highlighted) {
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .semantics { stateDescription = newTopicHighlightDescription }
            } else {
                Modifier
            },
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = m.listRowVertical)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagIndicator(flagType = topic.flagType, hasUnread = topic.hasUnread)
        Column(modifier = Modifier.fillMaxWidth()) {
            if (topic.isSticky || topic.isLocked) {
                TopicStatusBadge(text = topicBadgeText(topic))
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                fontWeight = if (topic.hasUnread == true) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
            )
            // #376 — common two-segment metadata line: `par <auteur> · N rép. · N p.` (left,
            // truncatable) + the last-reply timestamp pinned right and never truncated. Matches
            // the drapeaux template; the search list uses the same TopicMetadataLine.
            TopicMetadataLine(
                metadata = FlagMetadata(
                    start = stringResource(
                        R.string.category_topic_metadata,
                        topic.author,
                        topic.replyCount,
                        topic.totalPages,
                    ),
                    end = formatLastReplyTimestamp(topic.lastReplyAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = metadataColor,
            )
        }
    }
}

/** #1129 — tonal status badge shown before a sticky and/or locked topic title. */
@Composable
private fun TopicStatusBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Alpha of a read flag dot — same dimming contract as `FlagDot` (:core:ui FlagItem). */
private const val READ_FLAG_DOT_ALPHA = 0.35f

@Composable
private fun FlagIndicator(flagType: FlagType?, hasUnread: Boolean?) {
    // Reserve the same gutter width on every row, flagged or not, so titles stay
    // left-aligned across rows in mixed (auth) listings. When flagType is null the
    // gutter is an empty Spacer; when it is non-null we paint the colored dot inside
    // the same gutter and attach a contentDescription so screen readers and color-
    // blind users get the bucket name (color is otherwise the only signal).
    val gutter = Modifier
        .padding(end = 12.dp)
        .size(10.dp)
    if (flagType == null) {
        Spacer(modifier = gutter)
        return
    }
    // #329 — a read flag keeps its bucket colour but dims, so a vivid dot means
    // « unread » : same visual grammar as the Drapeaux tab. `hasUnread` is tri-state ;
    // null (state unknown, e.g. REST omitted the field) keeps the vivid dot rather
    // than wrongly claiming the topic was read.
    val base = FlagPalette.colorFor(flagType)
    val color = if (hasUnread == false) base.copy(alpha = READ_FLAG_DOT_ALPHA) else base
    val description = stringResource(
        when (flagType) {
            FlagType.CYAN -> R.string.category_flag_cyan
            FlagType.RED -> R.string.category_flag_red
            FlagType.FAVORITE -> R.string.category_flag_favorite
        },
    )
    Box(
        modifier = gutter
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun topicBadgeText(topic: TopicSummary): String {
    val sticky = stringResource(R.string.category_badge_sticky)
    val locked = stringResource(R.string.category_badge_locked)
    return when {
        topic.isSticky && topic.isLocked -> "$sticky · $locked"
        topic.isSticky -> sticky
        else -> locked
    }
}

@Composable
private fun PagerRow(
    currentPage: Int,
    pageCount: Int,
    onSelectPage: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            enabled = currentPage > 1,
            onClick = { onSelectPage(currentPage - 1) },
        ) {
            Text(text = stringResource(R.string.category_pager_previous))
        }
        Text(
            text = stringResource(R.string.category_pager_position, currentPage, pageCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            enabled = currentPage < pageCount,
            onClick = { onSelectPage(currentPage + 1) },
        ) {
            Text(text = stringResource(R.string.category_pager_next))
        }
    }
}
