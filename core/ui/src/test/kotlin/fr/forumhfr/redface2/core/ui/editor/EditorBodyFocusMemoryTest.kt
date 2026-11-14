package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBodyFocusMemoryTest {
    @Test
    fun configurationChangePreservesOpenImeWhenInsetsDisappearBeforePause() {
        val memory = EditorBodyFocusMemory()
        var changingConfigurations = false
        val field = EditorBodyFocusObservation(memory, imeVisible = true) { changingConfigurations }
        field.focusChanged(true)
        changingConfigurations = true
        field.imeChanged(false)
        field.focusChanged(false)
        assertEquals(EditorBodyFocusSnapshot(true, true), memory.snapshot)
        field.freeze()
        field.detach()
        assertEquals(EditorBodyFocusSnapshot(true, true), memory.snapshot)
        assertEquals(memory.snapshot, EditorBodyFocusObservation(memory, imeVisible = false).restore)
    }

    @Test
    fun imeClosedByTheUserBeforeConfigurationChangeStaysClosed() {
        val memory = EditorBodyFocusMemory()
        var changingConfigurations = false
        val field = EditorBodyFocusObservation(memory, imeVisible = true) { changingConfigurations }
        field.focusChanged(true)
        field.imeChanged(false)
        changingConfigurations = true
        field.imeChanged(false)
        field.focusChanged(false)
        field.freeze()
        field.detach()
        assertEquals(EditorBodyFocusSnapshot(true, false), memory.snapshot)
    }

    @Test
    fun pauseFreezesActualFocusAndImeBeforeDestructionNotifications() {
        val memory = EditorBodyFocusMemory()
        val field = EditorBodyFocusObservation(memory, imeVisible = true)
        field.focusChanged(true)
        field.freeze()
        field.imeChanged(false)
        field.focusChanged(false)
        field.detach()
        assertEquals(EditorBodyFocusSnapshot(focused = true, imeVisible = true), memory.snapshot)
    }

    @Test
    fun parentDetachFreezesBeforeTheDescendantLosesFocus() {
        val memory = EditorBodyFocusMemory()
        val field = EditorBodyFocusObservation(memory, imeVisible = false)
        field.focusChanged(true)
        field.detach()
        field.focusChanged(false)
        field.imeChanged(true)
        assertEquals(EditorBodyFocusSnapshot(focused = true, imeVisible = false), memory.snapshot)
    }

    @Test
    fun oldAttachmentCannotOverwriteTheNextOneOrThePendingRestoration() {
        val memory = EditorBodyFocusMemory()
        val old = EditorBodyFocusObservation(memory, imeVisible = true)
        old.focusChanged(true)
        old.freeze()
        val next = EditorBodyFocusObservation(memory, imeVisible = false)
        next.focusChanged(false) // The newly attached field has not been restored yet.
        next.imeChanged(false)
        old.resume()
        old.focusChanged(false)
        assertEquals(EditorBodyFocusSnapshot(true, true), memory.snapshot)
        assertEquals(memory.snapshot, next.restore)
        next.focusChanged(true)
        next.imeChanged(true)
        next.restored()
        assertEquals(EditorBodyFocusSnapshot(true, true), memory.snapshot)
    }

    @Test
    fun resumedFieldRecordsLaterUserFocusAndImeChanges() {
        val memory = EditorBodyFocusMemory()
        val field = EditorBodyFocusObservation(memory, imeVisible = true)
        field.focusChanged(true)
        field.freeze()
        field.imeChanged(false)
        field.resume()
        assertEquals(EditorBodyFocusSnapshot(true, false), memory.snapshot)
        field.focusChanged(false)
        assertEquals(EditorBodyFocusSnapshot(false, false), memory.snapshot)
    }

    @Test
    fun saverExcludesBodySnapshotAndNewSessionResetsAllFocusMemory() {
        val memory = EditorSurfaceFocusMemory().apply {
            autoFocusConsumed = true
            imeBeforeSurface = true
            body.snapshot = EditorBodyFocusSnapshot(true, true)
            preview.wasOpen = true
            preview.returnFocus = true
        }
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(EditorSurfaceFocusMemory.Saver) { scope.save(memory) }
        val restored = requireNotNull(EditorSurfaceFocusMemory.Saver.restore(requireNotNull(saved)))
        assertTrue(restored.autoFocusConsumed)
        assertTrue(restored.imeBeforeSurface)
        assertEquals(EditorBodyFocusSnapshot(), restored.body.snapshot)
        assertFalse(restored.preview.wasOpen)
        assertFalse(restored.preview.returnFocus)
        val obsolete = EditorBodyFocusObservation(memory.body, imeVisible = true)
        memory.reset()
        obsolete.focusChanged(true)
        obsolete.restored()
        assertFalse(memory.autoFocusConsumed)
        assertFalse(memory.imeBeforeSurface)
        assertFalse(memory.preview.wasOpen)
        assertFalse(memory.preview.returnFocus)
        assertNull(EditorBodyFocusObservation(memory.body, imeVisible = false).restore)
    }
}
