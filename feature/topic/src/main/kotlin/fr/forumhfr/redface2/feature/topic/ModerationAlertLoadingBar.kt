package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Initial alert read, overlaid by each host at the top of its content without moving that content. */
@Composable
fun ModerationAlertLoadingBar(visible: Boolean, modifier: Modifier = Modifier) {
    if (visible) {
        val label = stringResource(R.string.topic_alert_initial_loading)
        LinearProgressIndicator(
            modifier = modifier.zIndex(1f).fillMaxWidth().height(4.dp).semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        )
    }
}
