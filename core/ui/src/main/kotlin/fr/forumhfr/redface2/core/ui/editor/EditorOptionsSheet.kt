package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet hosting the HFR per-message option toggles (signature / smileys / e-mail
 * notification), opened from the « Options » action of an editor submit bar (post editor,
 * topic form, MP reply).
 *
 * The toggles used to live inline in the scrolled editor column ; moving them behind a
 * sheet reclaims vertical space around the draft field (dogfooding feedback) while
 * keeping the M3 idiom already used by the smiley picker — on phones, modal bottom
 * sheets are the recommended surface for a short list of controls. The [content] slot
 * receives each screen's existing options block, so the sheet stays a dumb container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorOptionsSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}
