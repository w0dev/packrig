package net.packrig.app.settings

import net.packrig.rig.RigProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RigProfileListTest {

    private fun p(id: String, name: String) = RigProfile(id = id, name = name, presetId = "ft891")
    private val four = listOf(p("1", "Alpha"), p("2", "Bravo"), p("3", "Charlie"), p("4", "Delta"))

    @Test
    fun upsertAddsUntilCapThenRejects() {
        val five = RigProfileList.upsert(four, p("5", "Echo"))!!
        assertEquals(5, five.size)
        assertNull(RigProfileList.upsert(five, p("6", "Foxtrot")))
    }

    @Test
    fun upsertReplacesExistingIdEvenAtCap() {
        val five = four + p("5", "Echo")
        val edited = RigProfileList.upsert(five, p("5", "Echo II"))!!
        assertEquals(5, edited.size)
        assertEquals("Echo II", edited.last().name)
    }

    @Test
    fun duplicateNameRejectedCaseInsensitiveExceptSelf() {
        assertNotNull(RigProfileList.nameError("alpha", four, selfId = "9"))
        assertNull(RigProfileList.nameError("alpha", four, selfId = "1"))
        assertNotNull(RigProfileList.nameError("  ", four, selfId = null))
        assertNull(RigProfileList.upsert(four, p("9", "ALPHA")))
    }

    @Test
    fun deleteRemovesById() {
        assertEquals(listOf("1", "3", "4"), RigProfileList.delete(four, "2").map { it.id })
    }

    @Test
    fun selectionClearsWhenSelectedRigDeleted() {
        val remaining = RigProfileList.delete(four, "1")
        // Deleting the selected rig deselects (None) — owner decision 2026-07-12.
        assertNull(RigProfileList.selectionAfterDelete(remaining, deletedId = "1", currentSelection = "1"))
        // Deleting an unrelated rig keeps the current selection.
        assertEquals("3", RigProfileList.selectionAfterDelete(remaining, deletedId = "9", currentSelection = "3"))
        // Nothing selected stays nothing.
        assertNull(RigProfileList.selectionAfterDelete(remaining, deletedId = "1", currentSelection = null))
        assertNull(RigProfileList.selectionAfterDelete(emptyList(), deletedId = "1", currentSelection = "1"))
    }
}
