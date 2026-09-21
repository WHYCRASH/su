package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityNodeIdentityTest {
    @Test
    fun `same view id with recycled row text is not the same node`() {
        val original = identity(text = "first row")
        val recycled = identity(text = "inserted new row")

        assertFalse(original.matches(recycled))
    }

    @Test
    fun `unique id cannot hide changed action semantics`() {
        val original = identity(uniqueId = "virtual-42", text = "old state")
        val refreshed = identity(uniqueId = "virtual-42", text = "new state")

        assertFalse(original.matches(refreshed))
        assertTrue(original.matches(identity(uniqueId = "virtual-42", text = "old state")))
    }

    @Test
    fun `blank semantic node is weak when window content changes`() {
        assertFalse(identity(text = "", description = "").strong)
        assertTrue(identity(text = "button").strong)
    }

    @Test
    fun `identity gaining a unique id is treated as replacement`() {
        assertFalse(identity(uniqueId = "").matches(identity(uniqueId = "virtual-42")))
    }

    @Test
    fun `truncated snapshot cannot promote semantic identity to globally unique`() {
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 1,
            ),
        )
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = true,
                snapshotTruncated = true,
                identityMatchCount = 1,
            ),
        )
    }

    @Test
    fun `complete snapshot requires exactly one semantic identity match`() {
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = false,
                identityMatchCount = 1,
            ),
        )
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = false,
                identityMatchCount = 2,
            ),
        )
    }

    private fun identity(
        uniqueId: String = "",
        text: String = "item",
        description: String = "",
    ): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
        uniqueId = uniqueId,
        windowId = 7,
        packageName = "example.app",
        className = "android.widget.TextView",
        viewId = "example.app:id/row",
        text = text,
        description = description,
        password = false,
    )
}
