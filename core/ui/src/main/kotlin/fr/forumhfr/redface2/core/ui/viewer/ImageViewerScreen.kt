package fr.forumhfr.redface2.core.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.browser.LocalAlwaysAskLinkApp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.motion.rememberAnimationsEnabled
import fr.forumhfr.redface2.core.ui.post.copyImageUrlToClipboard
import fr.forumhfr.redface2.core.ui.post.openImageUrlInBrowser
import fr.forumhfr.redface2.core.ui.post.sharePostImageUrl
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/** Fullscreen image surface for #182. Navigation and persistence remain owned by `:app`. */
@Composable
fun ImageViewerScreen(
    request: ImageViewerRequest,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val alwaysAskLinkApp = LocalAlwaysAskLinkApp.current
    val contentDescription = request.description ?: stringResource(R.string.image_viewer_default_description)
    val copiedFeedback = stringResource(R.string.post_image_menu_url_copied)
    val browserFailedFeedback = stringResource(R.string.browser_no_handler)
    val shareFailedFeedback = stringResource(R.string.post_image_menu_share_failed)
    var loadState by remember(request.sourceUrl) { mutableStateOf(ImageViewerLoadState.Loading) }
    var actionsVisible by remember(request.sourceUrl) { mutableStateOf(true) }
    val toggleActions = { actionsVisible = !actionsVisible }
    val toggleActionsLabel = stringResource(
        if (actionsVisible) R.string.image_viewer_hide_actions else R.string.image_viewer_show_actions,
    )
    val animationsEnabled = rememberAnimationsEnabled()

    ImmersiveSystemBarsEffect()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ZoomableRemoteImage(
            request = request,
            contentDescription = contentDescription,
            onLoadStateChanged = { loadState = it },
            onClick = toggleActions,
            modifier = Modifier
                .fillMaxSize()
                .testTag(IMAGE_VIEWER_IMAGE_TAG)
                .semantics {
                    onClick(label = toggleActionsLabel) {
                        toggleActions()
                        true
                    }
                },
        )

        ImageViewerLoadOverlay(
            state = loadState,
            onOpenBrowser = {
                openImageUrlInBrowser(
                    context = context,
                    url = request.externalUrl,
                    failureFeedback = browserFailedFeedback,
                    alwaysAsk = alwaysAskLinkApp,
                )
            },
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(
            visible = actionsVisible,
            enter = if (animationsEnabled) fadeIn(tween(ACTIONS_FADE_DURATION_MS)) else EnterTransition.None,
            exit = if (animationsEnabled) fadeOut(tween(ACTIONS_FADE_DURATION_MS)) else ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ImageViewerActionBar(
                actions = ImageViewerActions(
                    onClose = onClose,
                    onShare = { sharePostImageUrl(context, request.sourceUrl, shareFailedFeedback) },
                    onCopy = { copyImageUrlToClipboard(context, request.sourceUrl, copiedFeedback) },
                    onOpenBrowser = {
                        openImageUrlInBrowser(
                            context = context,
                            url = request.externalUrl,
                            failureFeedback = browserFailedFeedback,
                            alwaysAsk = alwaysAskLinkApp,
                        )
                    },
                    onSave = { onSave(request.sourceUrl) },
                ),
            )
        }
    }
}

@Composable
private fun ImageViewerLoadOverlay(
    state: ImageViewerLoadState,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ImageViewerLoadState.Loading -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxSize()
                .testTag(IMAGE_VIEWER_LOADING_TAG),
        ) {
            val loadingDescription = stringResource(R.string.image_viewer_loading)
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .testTag(IMAGE_VIEWER_PROGRESS_TAG)
                    .semantics { contentDescription = loadingDescription },
            )
        }

        ImageViewerLoadState.Error -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(24.dp)
                .testTag(IMAGE_VIEWER_ERROR_TAG),
        ) {
            Text(
                text = stringResource(R.string.image_viewer_error),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onOpenBrowser) {
                Text(stringResource(R.string.browser_open_action))
            }
        }

        ImageViewerLoadState.Ready -> Unit
    }
}

/** Groups the viewer action-bar callbacks so the composable keeps a short parameter list. */
@Immutable
private class ImageViewerActions(
    val onClose: () -> Unit,
    val onShare: () -> Unit,
    val onCopy: () -> Unit,
    val onOpenBrowser: () -> Unit,
    val onSave: () -> Unit,
)

@Composable
private fun ImageViewerActionBar(
    actions: ImageViewerActions,
    modifier: Modifier = Modifier,
) {
    // safeDrawing also includes the TOP cutout, which can remain after hiding system bars.
    // A bottom bar only needs bottom/side protection, including side navigation in landscape.
    // navigationBars respects visibility; never reserve navigationBarsIgnoringVisibility here.
    val actionInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .testTag(IMAGE_VIEWER_ACTIONS_TAG)
            .background(Color.Black.copy(alpha = ACTION_BAR_ALPHA))
            .windowInsetsPadding(actionInsets)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        ImageViewerAction(R.drawable.ic_ms_share, R.string.post_image_menu_share, actions.onShare)
        ImageViewerAction(
            R.drawable.ic_ms_content_copy,
            R.string.post_image_menu_copy_url,
            actions.onCopy,
        )
        ImageViewerAction(
            R.drawable.ic_ms_open_in_new,
            R.string.browser_open_action,
            actions.onOpenBrowser,
        )
        ImageViewerAction(R.drawable.ic_ms_download, R.string.post_image_menu_save, actions.onSave)
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = actions.onClose,
            modifier = Modifier
                .size(ACTION_TOUCH_TARGET)
                .background(Color.White.copy(alpha = CLOSE_CONTAINER_ALPHA), CircleShape),
        ) {
            RedfaceVectorIcon(
                resId = R.drawable.ic_close,
                contentDescription = stringResource(R.string.image_viewer_close),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ImageViewerAction(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ACTION_TOUCH_TARGET)) {
        RedfaceVectorIcon(
            resId = iconRes,
            contentDescription = stringResource(labelRes),
            tint = Color.White,
        )
    }
}

/** The only Telephoto call-site in the project, keeping replacement cost local to `:core:ui`. */
@Composable
internal fun ZoomableRemoteImage(
    request: ImageViewerRequest,
    contentDescription: String,
    onLoadStateChanged: (ImageViewerLoadState) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Telephoto 0.19 can retain the first non-null tap callback across image changes.
    val currentOnClick by rememberUpdatedState(onClick)
    val imageRequest = remember(context, request) {
        ImageRequest.Builder(context)
            .data(request.sourceUrl)
            .placeholderMemoryCacheKey(MemoryCache.Key(request.previewUrl))
            .apply {
                if (!request.diskCache) diskCachePolicy(CachePolicy.DISABLED)
            }
            .listener(
                onSuccess = { _, _ -> onLoadStateChanged(ImageViewerLoadState.Ready) },
                onError = { _, _ -> onLoadStateChanged(ImageViewerLoadState.Error) },
            )
            .build()
    }
    if (request.diskCache) {
        ZoomableAsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onClick = { currentOnClick() },
            onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = DOUBLE_TAP_ZOOM),
            modifier = modifier,
        )
    } else {
        MemoryOnlyZoomableImage(
            imageRequest = imageRequest,
            contentDescription = contentDescription,
            onClick = { currentOnClick() },
            modifier = modifier,
        )
    }
}

/**
 * Telephoto 0.19 rewrites a Coil `DISABLED` disk policy to `WRITE_ONLY` to obtain a file for
 * sub-sampling. MP media must never touch disk (#1096), so this branch combines Coil's regular
 * memory-only painter with Telephoto's gesture modifier. Public media keeps [ZoomableAsyncImage]
 * and its tiled full-resolution path above; private media deliberately trades sub-sampling for the
 * stronger privacy boundary.
 */
@Composable
private fun MemoryOnlyZoomableImage(
    imageRequest: ImageRequest,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoomableState = rememberZoomableState()
    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onSuccess = { success ->
            zoomableState.setContentLocation(
                ZoomableContentLocation.scaledInsideAndCenterAligned(success.painter.intrinsicSize),
            )
        },
        modifier = modifier.zoomable(
            state = zoomableState,
            onClick = { onClick() },
            onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = DOUBLE_TAP_ZOOM),
        ),
    )
}

@Composable
private fun ImmersiveSystemBarsEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    val window = context.findActivity()?.window
    DisposableEffect(window, view) {
        val snapshot = window?.let { hideSystemBars(it, view) }
        onDispose {
            if (window != null && snapshot != null) restoreSystemBars(window, view, snapshot)
        }
    }
}

private data class SystemBarsSnapshot(
    val statusBarsVisible: Boolean,
    val navigationBarsVisible: Boolean,
    val behavior: Int,
)

private fun hideSystemBars(window: Window, view: View): SystemBarsSnapshot {
    val controller = WindowCompat.getInsetsController(window, view)
    val insets = ViewCompat.getRootWindowInsets(view)
    val snapshot = SystemBarsSnapshot(
        statusBarsVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
        navigationBarsVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true,
        behavior = controller.systemBarsBehavior,
    )
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
    return snapshot
}

private fun restoreSystemBars(window: Window, view: View, snapshot: SystemBarsSnapshot) {
    val controller = WindowCompat.getInsetsController(window, view)
    restoreBarVisibility(controller, WindowInsetsCompat.Type.statusBars(), snapshot.statusBarsVisible)
    restoreBarVisibility(controller, WindowInsetsCompat.Type.navigationBars(), snapshot.navigationBarsVisible)
    controller.systemBarsBehavior = snapshot.behavior
}

private fun restoreBarVisibility(
    controller: WindowInsetsControllerCompat,
    type: Int,
    wasVisible: Boolean,
) {
    if (wasVisible) controller.show(type) else controller.hide(type)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal enum class ImageViewerLoadState { Loading, Ready, Error }

internal const val IMAGE_VIEWER_IMAGE_TAG = "image-viewer-image"
internal const val IMAGE_VIEWER_LOADING_TAG = "image-viewer-loading"
internal const val IMAGE_VIEWER_PROGRESS_TAG = "image-viewer-progress"
internal const val IMAGE_VIEWER_ERROR_TAG = "image-viewer-error"
internal const val IMAGE_VIEWER_ACTIONS_TAG = "image-viewer-actions"
private const val DOUBLE_TAP_ZOOM = 2f
private const val ACTIONS_FADE_DURATION_MS = 150
private const val ACTION_BAR_ALPHA = 0.64f
private const val CLOSE_CONTAINER_ALPHA = 0.2f
private val ACTION_TOUCH_TARGET = 48.dp
