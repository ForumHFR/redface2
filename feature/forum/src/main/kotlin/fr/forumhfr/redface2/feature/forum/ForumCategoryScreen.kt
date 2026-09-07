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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.model.FlagType
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

    // #1130 — system/gesture back leaves the search mode first (clearing the query) instead of
    // popping the category route, mirroring the settings search shell. Disabled when the search
    // is closed so normal back navigation proceeds. The active state lives in the ViewModel so
    // the close/clear logic stays unit-testable without a Compose harness.
    BackHandler(enabled = state.searchActive) { viewModel.closeSearch() }

    ForumCategoryContent(
        state = state,
        // #206 workaround — highlight only on the listing page/subcat reached immediately after
        // create. If the user changes page or subcat, the route hint is ignored so an unrelated
        // same-title topic is not highlighted there.
        highlightTitle = routeScopedHighlightTitle(
            request = request,
            selectedSubcat = state.selectedSubcat,
            page = state.page,
        ),
        onOpenTopic = onOpenTopic,
        onCreateTopic = onCreateTopic,
        callbacks = ForumCategoryCallbacks(
            onSelectSubcategory = viewModel::selectSubcategory,
            onSelectFlagFilter = viewModel::selectFlagFilter,
            onQueryChange = viewModel::updateSearchQuery,
            onOpenSearch = viewModel::openSearch,
            onCloseSearch = viewModel::closeSearch,
            onRefresh = viewModel::refresh,
            onSelectPage = viewModel::selectPage,
            onSetMenusCollapsed = viewModel::setMenusCollapsed,
            onSetStickyTopicsCollapsed = viewModel::setStickyTopicsCollapsed,
        ),
    )
}

/**
 * ViewModel-facing actions of [ForumCategoryContent], hoisted so the stateless body can be
 * mounted without a [CategoryViewModel] (JVM geometry / capture tests, #1149). Every action
 * defaults to a no-op so a test host only wires what it exercises. Same shape as
 * `PrivateMessageThreadCallbacks` in `:feature:messages`.
 */
@Suppress("LongParameterList") // One state-hoisted action per independent control on the surface.
internal data class ForumCategoryCallbacks(
    val onSelectSubcategory: (Int?) -> Unit = {},
    val onSelectFlagFilter: (CategoryFlagFilter) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onOpenSearch: () -> Unit = {},
    val onCloseSearch: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectPage: (Int) -> Unit = {},
    val onSetMenusCollapsed: (Boolean) -> Unit = {},
    val onSetStickyTopicsCollapsed: (Boolean) -> Unit = {},
)

/**
 * Stateless body of [ForumCategoryScreen]: the create-topic FAB [Scaffold] and the category
 * column (title, sub-category chips, flag filter, search, pull-to-refresh topic list). Split
 * from the screen so it can be mounted in JVM tests without Hilt — nothing mounted this surface
 * before #1149 (cf. the rendering note in `ForumCategoryLayoutTest`).
 *
 * Insets (#1149) : the [Scaffold] keeps its default `contentWindowInsets` (system bars), so the
 * `padding` it hands to the content ALREADY carries the status bar (top) and the navigation bar
 * (bottom, or a side on 3-button landscape) — minus whatever an ancestor consumed (the app shell
 * consumes the bottom navigation-bar inset under its bottom bar, #529, so that edge resolves to
 * 0 on a phone and to the real bar height under a rail / drawer). The column applies that
 * padding ONCE and must not add `statusBarsPadding()` / `navigationBarsPadding()` on top — that
 * doubled the top margin everywhere and the bottom / side margins wherever the shell did not
 * consume them. Same rule as the topic reader since #285. The #1131 FAB clearance is unrelated:
 * it is the LazyColumn's own `contentPadding` ([forumListContentPadding]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForumCategoryContent(
    state: CategoryUiState,
    highlightTitle: String?,
    onOpenTopic: (TopicSummary) -> Unit,
    onCreateTopic: (cat: Int, subcat: Int?) -> Unit,
    callbacks: ForumCategoryCallbacks,
) {
    // #482 — hoisted so the FAB can collapse to icon-only once the listing is scrolled
    // (Azgor: the extended FAB is "presque envahissant"). Shared with the LazyColumn below.
    val listState = rememberLazyListState()
    // Expanded only while resting at the very top; any scroll shrinks it out of the way.
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            if (state.canCreateTopic) {
                val createTopicLabel = stringResource(R.string.category_create_topic)
                ExtendedFloatingActionButton(
                    onClick = { onCreateTopic(state.cat, state.selectedSubcat) },
                    expanded = fabExpanded,
                    text = { Text(text = createTopicLabel) },
                    // Material 3 takes the accessible label from the icon, even when expanded.
                    icon = {
                        Text(text = "+", modifier = Modifier.clearAndSetSemantics {
                            contentDescription = createTopicLabel
                        })
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // #1149 — the Scaffold padding is the single inset source (see the KDoc above).
                .padding(padding)
                .testTag(FORUM_CATEGORY_CONTENT_TAG),
        ) {
            ForumCategoryHeader(state, callbacks)

            // In flag-filter mode the bucket listing is the source (and the pager is hidden,
            // buckets are not paginated); ALL keeps the normal paginated listing.
            val filterActive = state.flagFilter != CategoryFlagFilter.ALL
            val activeTopics = if (filterActive) state.flagFilterTopics else state.topics
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = callbacks.onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                TopicsBody(
                    state = if (state.layoutPreferencesReady) activeTopics else TopicsUiState.Loading,
                    listState = listState,
                    filteredTopics = state.filteredTopics,
                    searchQuery = state.searchQuery,
                    onOpenTopic = onOpenTopic,
                    onRetry = callbacks.onRefresh,
                    onSelectPage = callbacks.onSelectPage,
                    currentPage = state.page,
                    pageCount = state.pageCount,
                    showPager = !filterActive,
                    // Flag-filter buckets stay flat, including their sticky topics.
                    filterActive = filterActive,
                    stickyCollapsed = state.stickyTopicsCollapsed && !state.searchActive,
                    onToggleSticky = if (state.searchActive) null else {
                        { callbacks.onSetStickyTopicsCollapsed(!state.stickyTopicsCollapsed) }
                    },
                    // #1131 — reserve the FAB clearance so the pager clears the « + » button.
                    // The FAB is rendered only when the user can create a topic.
                    contentPadding = forumListContentPadding(reserveFabSpace = state.canCreateTopic),
                    highlightTitle = highlightTitle,
                )
            }
        }
    }
}

/** Test tag of the inset-padded content column of [ForumCategoryContent] (#1149 geometry proof). */
internal const val FORUM_CATEGORY_CONTENT_TAG = "forum_category_content"

/**
 * Compose composables idiomatically take many small focused params (state slice +
 * callbacks + pagination and layout choices). Keep this suppression local to the
 * state-hoisted list body instead of relaxing the project-wide parameter limit.
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
    stickyCollapsed: Boolean,
    onToggleSticky: (() -> Unit)?,
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
            val showStickyHeader = sections.shouldShowStickyHeader(filterActive)
            PreserveStickyScrollAnchor(listState, sections, showStickyHeader && stickyCollapsed)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().testTag(FORUM_CATEGORY_LIST_TAG),
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
                    if (showStickyHeader) {
                        item(key = "sticky_header") {
                            ForumStickyTopicsHeader(sections.sticky.size, stickyCollapsed, onToggleSticky)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (!stickyCollapsed) {
                        items(sections.sticky, key = TopicSummary::topicId) { topic ->
                            TopicRow(
                                topic = topic,
                                highlighted = matchesHighlightedTitle(topic, highlightTitle),
                                onClick = { onOpenTopic(topic) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                    item(key = "pager") {
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
 * Stable topic keys keep a regular anchor in place. If a removed sticky was the anchor, request
 * the header BEFORE the next remeasure. Only the transition to hidden triggers this fallback.
 */
@Composable
private fun PreserveStickyScrollAnchor(listState: LazyListState, sections: TopicSections, hidden: Boolean) {
    val previouslyHidden = remember { booleanArrayOf(hidden) }
    val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
    SideEffect {
        if (hidden && !previouslyHidden[0] && sections.sticky.any { it.topicId == anchor }) {
            listState.requestScrollToItem(0)
        }
        previouslyHidden[0] = hidden
    }
}

internal const val FORUM_CATEGORY_LIST_TAG = "forum_category_list"

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
