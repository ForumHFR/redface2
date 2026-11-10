package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal val privateMessageReplyCheckpointKeys = setOf(
    "messageReplyEditor.resumeOwner",
    "messageReplyEditor.resumeKey",
    "messageReplyEditor.resumeVersion",
    "messageReplyEditor.selectionStart",
    "messageReplyEditor.selectionEnd",
    "messageReplyEditor.scroll",
)

/** A closed metadata schema, with a content check on every value independently of Compose's registry. */
internal fun assertPrivateMessageReplySavedState(saved: SavedStateHandle, privateFragments: List<String>) {
    val allowedKeys = privateMessageReplyCheckpointKeys + "draft_restore_offer_fingerprint"
    val integerKeys = setOf(
        "messageReplyEditor.selectionStart", "messageReplyEditor.selectionEnd", "messageReplyEditor.scroll",
    )
    assertTrue(
        "Unexpected reply saved-state keys: ${saved.keys() - allowedKeys}", saved.keys().all { it in allowedKeys },
    )
    saved.keys().forEach { key ->
        val value = saved.get<Any?>(key)
        privateFragments.forEach { fragment ->
            require(fragment.isNotEmpty())
            assertFalse("Private content in SavedStateHandle[$key]", value.toString().contains(fragment))
        }
        if (key in integerKeys) {
            assertTrue("Viewport metadata must be an Int: $key", value is Int)
        } else {
            assertTrue("Reply identity metadata must be a String: $key", value is String)
            assertTrue("Reply metadata must stay bounded: $key", value.toString().length <= MAX_METADATA_LENGTH)
        }
    }
}

private const val MAX_METADATA_LENGTH = 128
