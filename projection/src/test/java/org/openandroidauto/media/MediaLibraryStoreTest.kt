package org.openandroidauto.media

import org.junit.Assert.*
import org.junit.Test

class MediaLibraryStoreTest {
    @Test fun `replacement releases only removed prior readable grants`() {
        assertEquals(setOf("removed"), MediaLibraryStore.removedPersistedReads(
            listOf("removed", "retained", "session-only", "write-only"),
            listOf("retained", "new"), setOf("removed", "retained", "new", "unrelated")))
    }
    @Test fun `clear deduplicates old grants and leaves unrelated resources alone`() {
        assertEquals(setOf("one", "two"), MediaLibraryStore.removedPersistedReads(
            listOf("one", "one", "two", "session-only"), emptyList(), setOf("one", "two", "unrelated")))
    }
    @Test fun `unchanged or reordered selections keep their read permissions`() {
        assertTrue(MediaLibraryStore.removedPersistedReads(listOf("one", "two"), listOf("two", "one"), setOf("one", "two")).isEmpty())
    }
    @Test fun `missing persisted or prior list never releases new grants`() {
        assertTrue(MediaLibraryStore.removedPersistedReads(listOf("one"), emptyList(), emptySet()).isEmpty())
        assertTrue(MediaLibraryStore.removedPersistedReads(emptyList(), listOf("new"), setOf("new", "unrelated")).isEmpty())
    }
}
