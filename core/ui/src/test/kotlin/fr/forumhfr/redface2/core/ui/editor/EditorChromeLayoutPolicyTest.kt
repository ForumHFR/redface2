package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorChromeLayoutPolicyTest {
    private val compact = EditorChromeLayoutRequest(360.dp, 139.dp, compactHeight = true,
        hasHeader = true, statusWidths = listOf(48.dp, 48.dp))

    @Test
    fun `ED25 at 360 dp retains header and preview before states and preserves the scrolling target`() {
        val result = allocateEditorChrome(compact)
        assertEquals(listOf(EditorFixedSlot.Header, EditorFixedSlot.Preview), result.fixedSlots)
        assertEquals(listOf(EditorFixedSlot.FirstStatus, EditorFixedSlot.SecondStatus), result.overflowSlots)
        assertEquals(93.dp, result.scrollingWidth)
        assertTrue(result.hasMinimumContentWidth)
    }

    @Test
    fun `ED25 each exact boundary retains one more slot without shrinking 48 dp`() {
        val priority = EditorFixedSlot.entries
        (0..4).forEach { count ->
            val width = 139.dp + 32.dp + 48.dp + 48.dp * count
            val exact = allocateEditorChrome(compact.copy(availableWidth = width))
            assertEquals(priority.take(count), exact.fixedSlots)
            assertEquals(priority.drop(count), exact.overflowSlots)
            assertEquals(48.dp, exact.scrollingWidth)
            if (count > 0) {
                val below = allocateEditorChrome(compact.copy(availableWidth = width - 0.1.dp))
                assertEquals(priority.take(count - 1), below.fixedSlots)
                assertTrue(below.scrollingWidth >= 48.dp)
            }
        }
    }

    @Test
    fun `ED25 landscape uses useful width and measured badges rather than orientation`() {
        val landscape = compact.copy(availableWidth = 760.dp - 24.dp - 48.dp,
            statusWidths = listOf(80.dp, 56.dp))
        val result = allocateEditorChrome(landscape)
        assertEquals(EditorFixedSlot.entries, result.fixedSlots)
        assertEquals(285.dp, result.scrollingWidth)
        assertTrue(result.overflowSlots.isEmpty())
        val narrow = allocateEditorChrome(landscape.copy(availableWidth = 300.dp))
        assertEquals(listOf(EditorFixedSlot.Header), narrow.fixedSlots)
        assertEquals(81.dp, narrow.scrollingWidth)
    }

    @Test
    fun `ED25 send is never demoted and impossible widths remain explicit`() {
        val result = allocateEditorChrome(compact.copy(availableWidth = 200.dp))
        assertTrue(result.fixedSlots.isEmpty())
        assertEquals(EditorFixedSlot.entries, result.overflowSlots)
        assertEquals(29.dp, result.scrollingWidth)
        assertFalse(result.hasMinimumContentWidth)
        assertEquals(0.dp, allocateEditorChrome(compact.copy(availableWidth = 100.dp)).scrollingWidth)
    }

    @Test
    fun `ED25 no header and zero states reserve preview only`() {
        val result = allocateEditorChrome(compact.copy(hasHeader = false, statusWidths = emptyList()))
        assertEquals(listOf(EditorFixedSlot.Preview), result.fixedSlots)
        assertEquals(141.dp, result.scrollingWidth)
        assertTrue(result.overflowSlots.isEmpty())
    }

    @Test
    fun `ED30 event envelope takes priority over fixed slots`() {
        val result = allocateEditorChrome(compact.copy(minimumContentWidth = 181.dp + 8.dp))
        assertTrue(result.fixedSlots.isEmpty())
        assertEquals(189.dp, result.scrollingWidth)
        assertEquals(EditorFixedSlot.entries, result.overflowSlots)
    }

    @Test
    fun `ED38 regular height exposes shortcut third and relegates options then image`() {
        listOf(115.dp, 139.dp).forEach { measuredSendWidth ->
            val result = allocateEditorChrome(compact.copy(compactHeight = false, submitWidth = measuredSendWidth))
            assertTrue(result.fixedSlots.isEmpty())
            assertTrue(result.overflowSlots.isEmpty())
            val actions = editorNormalActions(hasShortcut = true)
            assertEquals(EditorSecondaryAction.entries, actions)
            val visibleCount = if (measuredSendWidth == 115.dp) 4 else 3
            assertEquals(actions.take(visibleCount), actions.take((result.scrollingWidth / 48.dp).toInt()))
            assertEquals(EditorSecondaryAction.Options, actions.last())
        }
        assertEquals(listOf(EditorSecondaryAction.Format, EditorSecondaryAction.Smileys,
            EditorSecondaryAction.Image, EditorSecondaryAction.Options), editorNormalActions(hasShortcut = false))
    }

    @Test
    fun `ED25 allocations stay bounded across widths and measured send sizes`() {
        (100..800 step 7).forEach { width ->
            listOf(115.dp, 139.dp, 180.dp).forEach { send ->
                val result = allocateEditorChrome(compact.copy(availableWidth = width.dp, submitWidth = send))
                assertEquals(EditorFixedSlot.entries, result.fixedSlots + result.overflowSlots)
                assertTrue(result.scrollingWidth >= 0.dp)
                if (result.fixedSlots.isNotEmpty()) assertTrue(result.scrollingWidth >= 48.dp)
                val allocated = 32.dp + send + 48.dp * result.fixedSlots.size + result.scrollingWidth
                if (width.dp >= 32.dp + send) assertEquals(width.dp, allocated)
            }
        }
    }
}
