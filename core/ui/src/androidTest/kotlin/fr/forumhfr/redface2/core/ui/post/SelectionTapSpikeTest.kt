package fr.forumhfr.redface2.core.ui.post

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #958 (Lot 2) — né spike de la garde sélection (cadrage Sol), désormais le test DURABLE de
 * [AMENDEMENT-Lot2-1] : quand une sélection de texte est ACTIVE dans un [SelectionContainer], un
 * `clickable`/`combinedClickable` enfant REÇOIT son premier tap (`afterFirst == 1`) — Compose ne
 * consomme pas le tap, aucune garde « sélection active » n'est possible en stable 1.11.x, donc le
 * tap sur une image liée ouvre son lien et referme la sélection, comme un lien texte (§5 amendé).
 * Robolectric ne peut pas l'exécuter (crash magnifier) → instrumenté connecté (S10e), contenu
 * synthétique, aucun réseau. Les compteurs sont aussi écrits dans logcat (tag SPIKE958).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SelectionTapSpikeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val longText = "Ceci est une phrase selectionnable avec plusieurs mots a selectionner"

    @Test
    fun baseline_clickable_tap_without_selection() {
        val taps = mutableStateOf(0)
        setTarget(taps, combined = false)
        rule.onNodeWithTag("target").performTouchInput { click() }
        rule.waitForIdle()
        Log.i("SPIKE958", "baseline clickable (sans selection) : taps=${taps.value}")
        assertEquals("un tap simple doit atteindre le clickable sans selection", 1, taps.value)
    }

    @Test
    fun clickable_tap_after_active_selection() {
        val taps = mutableStateOf(0)
        setTarget(taps, combined = false)
        rule.onNodeWithTag("txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("target").performTouchInput { click() }
        rule.waitForIdle()
        val afterFirst = taps.value
        rule.onNodeWithTag("target").performTouchInput { click() }
        rule.waitForIdle()
        Log.i("SPIKE958", "clickable APRES selection : apres1erTap=$afterFirst apres2eTap=${taps.value}")
        // [AMENDEMENT-Lot2-1] : le tap atteint le clickable MALGRÉ la sélection active (pas de garde
        // possible en Compose stable). Preuve durable : le 1er tap vaut exactement 1.
        assertEquals("le tap doit atteindre le clickable même en sélection active", 1, afterFirst)
    }

    @Test
    fun combinedClickable_tap_after_active_selection() {
        val taps = mutableStateOf(0)
        setTarget(taps, combined = true)
        rule.onNodeWithTag("txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("target").performTouchInput { click() }
        rule.waitForIdle()
        val afterFirst = taps.value
        rule.onNodeWithTag("target").performTouchInput { click() }
        rule.waitForIdle()
        Log.i("SPIKE958", "combinedClickable APRES selection : apres1erTap=$afterFirst apres2eTap=${taps.value}")
        assertEquals("le tap doit atteindre le combinedClickable même en sélection active", 1, afterFirst)
    }

    private fun setTarget(taps: androidx.compose.runtime.MutableState<Int>, combined: Boolean) {
        rule.setContent {
            SelectionContainer {
                Column {
                    Text(longText, modifier = Modifier.testTag("txt"))
                    Spacer(Modifier.height(24.dp))
                    val mod = if (combined) {
                        Modifier.combinedClickable(onLongClick = {}) { taps.value++ }
                    } else {
                        Modifier.clickable { taps.value++ }
                    }
                    Text("CIBLE", modifier = Modifier.testTag("target").size(120.dp).then(mod))
                }
            }
        }
    }
}
