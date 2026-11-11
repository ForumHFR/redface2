package fr.forumhfr.redface2.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.ui.editor.LocalEditorPreviewState
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorPreview
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.LocalEditorTooltipHost
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.rememberEditorWindowMetrics
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/**
 * Topic-level form screen. Live for [TopicFormMode.EditFirstPost] (Phase 2D
 * #148) and [TopicFormMode.New] (Phase 2E #149).
 *
 * The shared chrome keeps the body alongside preview and mounted beneath header and event panels.
 * The ViewModel owns the session, picker and transient host memory across rotation.
 */
@Composable
@Suppress("LongParameterList") // One callback per navigation outcome — each wired to a distinct :app pop.
fun TopicFormScreen(
    request: TopicFormRequest,
    onSubmitSucceeded: (targetPage: Int?, scrollTo: Int?, operation: PostSubmitOperation) -> Unit,
    // #206 workaround — `subject` is the exact posted title, forwarded to the category
    // listing so it can highlight the freshly-created row (HFR never returns the new id).
    onNewTopicCreated: (cat: Int, subcat: Int, newTopicId: Int?, newNumreponse: Int?, subject: String) -> Unit,
    // #803 pattern (state-hygiene audit 2026-07-05) — pops this form AFTER the ViewModel flushed
    // the draft (CloseCommitted). `:app` supplies the navigation callback, as for the post.
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: TopicFormViewModel = hiltViewModel<TopicFormViewModel, TopicFormViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.submit(TopicFormIntent.Started)
                Lifecycle.Event.ON_STOP -> viewModel.submit(TopicFormIntent.Stopped)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val bodyScroll = viewModel.bodyScrollState
    val measuredViewport = bodyScroll.maxValue to bodyScroll.viewportSize
    LaunchedEffect(viewModel, state.isInitializing, measuredViewport) {
        if (state.isInitializing || measuredViewport.first == Int.MAX_VALUE) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (measuredViewport == (bodyScroll.maxValue to bodyScroll.viewportSize)) viewModel.restoreBodyScroll()
    }
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnNewTopicCreated by rememberUpdatedState(onNewTopicCreated)
    val currentOnSubmitSucceeded by rememberUpdatedState(onSubmitSucceeded)
    val context = LocalContext.current
    val newTopicCreatedFallback = stringResource(R.string.editor_new_topic_created)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TopicFormEffect.SubmitSucceeded ->
                    currentOnSubmitSucceeded(effect.targetPage, effect.scrollTo, effect.operation)
                is TopicFormEffect.NewTopicCreated -> {
                    if (effect.newTopicId == null) {
                        // Fallback path (#206, the only real path): the create succeeded but
                        // HFR's refresh URL carried no `sujet_{id}_{page}` segment, so the
                        // repository could not extract the new topic id (confirmed always the
                        // case for create — #214). Surface a sober Toast so the user knows the
                        // POST went through before landing back on the category listing, which
                        // will highlight the freshly-created row by exact-title match.
                        Toast.makeText(context, newTopicCreatedFallback, Toast.LENGTH_LONG).show()
                    }
                    currentOnNewTopicCreated(
                        effect.cat,
                        effect.subcat,
                        effect.newTopicId,
                        effect.newNumreponse,
                        effect.subject,
                    )
                }
                TopicFormEffect.CloseCommitted -> currentOnClose?.invoke()
            }
        }
    }

    CompositionLocalProvider(LocalEditorPreviewState provides viewModel.previewLayout) {
        TopicFormContent(
            state = state,
            bodyFieldState = viewModel.bodyFieldState,
            bodyInputTransformation = viewModel.bodyInputTransformation,
            bodyScrollState = viewModel.bodyScrollState,
            onIntent = remember(viewModel) { { intent: TopicFormIntent -> viewModel.submit(intent) } },
            smileyPicker = viewModel.smileyPicker,
            modifier = modifier,
            chromeEvents = viewModel.editorEvents,
            chromeMemory = viewModel.chromeMemory,
            bodyCaretRevealResetKey = remember(viewModel) { { viewModel.bodyCaretRevealResetKey } },
        )
    }
}

@Composable
@Suppress("LongParameterList") // Same live body, launcher and host ports as PostEditorContent.
internal fun TopicFormContent(
    state: TopicFormState,
    bodyFieldState: TextFieldState,
    bodyInputTransformation: InputTransformation?,
    onIntent: (TopicFormIntent) -> Unit,
    smileyPicker: SmileyPickerController,
    modifier: Modifier = Modifier,
    bodyScrollState: ScrollState = rememberScrollState(),
    chromeEvents: TopicFormEvents = remember { TopicFormEvents() },
    chromeMemory: TopicFormChromeMemory = remember { TopicFormChromeMemory() },
    windowMetrics: EditorWindowMetrics? = null,
    bodyCaretRevealResetKey: () -> Int = { 0 },
) {
    val source = rememberUpdatedState(state)
    val dispatch = rememberUpdatedState(onIntent)
    // Register before initialization and outside every transient surface, as for the post.
    val adapter = remember { arrayOfNulls<TopicFormChromeAdapter>(1) }
    val launchPicker = rememberEditorImagePicker(state.imagePickerMode,
        onCancelled = { adapter[0]?.host?.closeSurface() }, onEvent = { adapter[0]?.picker(it) })
    val currentLaunch = rememberUpdatedState(launchPicker)
    val host = remember(bodyFieldState, chromeEvents, chromeMemory, smileyPicker) {
        TopicFormChromeAdapter({ source.value }, bodyFieldState, chromeEvents, { dispatch.value(it) },
            { currentLaunch.value() }, smileyPicker::open, chromeMemory)
    }
    adapter[0] = host
    val activity = LocalActivity.current
    val measuredWindow = activity?.let { rememberEditorWindowMetrics(it).value }
    val window = windowMetrics ?: measuredWindow
    if (state.isInitializing || window == null) {
        Surface(modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    CompositionLocalProvider(LocalEditorTooltipHost provides remember { EditorTooltipHost() }) {
        TopicFormChrome(host, bodyFieldState, bodyInputTransformation, bodyScrollState, smileyPicker, window,
            modifier, bodyCaretRevealResetKey)
    }
}

@Composable
@Suppress("LongParameterList") // Screen assembly retains the live field, scroll and host-owned surfaces.
private fun TopicFormChrome(
    adapter: TopicFormChromeAdapter,
    body: TextFieldState,
    transformation: InputTransformation?,
    scroll: ScrollState,
    smileys: SmileyPickerController,
    window: EditorWindowMetrics,
    modifier: Modifier,
    bodyCaretRevealResetKey: () -> Int,
) {
    val host = adapter.host
    val state = adapter.state.source
    val tooltips = requireNotNull(LocalEditorTooltipHost.current)
    val back = {
        adapter.back(EditorBackState(tooltipOpen = tooltips.isVisible)) {
            if (it == EditorBackAction.CloseTooltip) tooltips.dismiss()
        }
    }
    val focus = remember { FocusRequester() }
    EditorScaffold(body, window, adapter.title(), adapter.context(), adapter.chrome(), host.barActions,
        host.submitState, host.submitActions, host.imageActions, host::closeRequested, modifier = modifier,
        onOpenHeader = adapter::openHeader, headerLabel = adapter.headerLabel(), bodyFocusRequester = focus,
        bodyScrollState = scroll, inputTransformation = transformation, autoFocus = true,
        surfaceFocus = host.focusMemory,
        bodyFocusRestoreAllowed = host.surface == null && host.detail == null,
        bodyCaretRevealResetKey = bodyCaretRevealResetKey, onSystemBack = back,
        fieldArea = { draft ->
            EditorPreview(state.isPreviewVisible, state.preview, body, focus, PostMediaDiskCachePolicy.ENABLED,
                stringResource(R.string.editor_preview_title), Modifier.testTag("topic-form-preview"), draft)
        }, overlay = { restore, content ->
            TopicFormChromeSurfaces(adapter, body, smileys, restore, window.compactHeight, content)
            // Register after bar/panels so activity Back uses the same ED-85 resolver on every level.
            BackHandler(onBack = back)
        })
}

/**
 * Material 3 dropdown picker over the [TopicFormSubcategoryChoice] list. The
 * `Aucune` option (`id == null`) is filtered out — the wire submit refuses
 * `subcat=""` and we don't want the user to see a choice that cannot be
 * submitted. Placeholder shown until the user picks something.
 *
 * Implemented with Material 3 `ExposedDropdownMenuBox` so the read-only field
 * remains reliably tappable and accessible in both New and EditFirstPost modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubcategoryDropdown(
    choices: List<fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice>,
    selectedSubcat: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickable = remember(choices) { choices.filter { it.id != null } }
    val placeholder = stringResource(R.string.editor_topic_subcat_picker_placeholder)
    val selectedLabel = pickable.firstOrNull { it.id == selectedSubcat }?.label ?: placeholder
    var expanded by remember { mutableStateOf(false) }
    val menuEnabled = enabled && pickable.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = menuEnabled && it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_topic_subcat_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = menuEnabled),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            pickable.forEach { choice ->
                val id = choice.id ?: return@forEach
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
