package net.packrig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotMarkerTrackerTest {
    @Test fun boundary_crossing_records_mark_at_bottom_with_new_slot_start() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)          // slot [0, 15000)
        tracker.onColumn(15_100L)          // first column of slot [15000, 30000)
        val marks = tracker.markers()
        assertEquals(1, marks.size)
        assertEquals(319, marks[0].row)
        assertEquals(15_000L, marks[0].slotStartEpochMillis)
    }

    @Test fun non_boundary_columns_shift_marks_up_and_add_nothing() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)          // mark at row 319
        tracker.onColumn(15_200L)
        tracker.onColumn(15_300L)
        val marks = tracker.markers()
        assertEquals(1, marks.size)
        assertEquals(317, marks[0].row)
    }

    @Test fun marks_scrolled_past_the_top_are_dropped() {
        val tracker = SlotMarkerTracker(history = 3)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)          // mark at row 2
        tracker.onColumn(15_200L)          // row 1
        tracker.onColumn(15_300L)          // row 0
        tracker.onColumn(15_400L)          // off the top
        assertTrue(tracker.markers().isEmpty())
    }

    @Test fun first_column_ever_records_no_mark() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(15_100L)
        assertTrue(tracker.markers().isEmpty())
    }

    @Test fun clear_empties_marks_and_next_column_records_none() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)
        tracker.clear()
        assertTrue(tracker.markers().isEmpty())
        tracker.onColumn(45_100L)          // first column after clear: no mark
        assertTrue(tracker.markers().isEmpty())
    }

    @Test fun consecutive_boundaries_each_get_their_own_mark() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)          // mark A
        tracker.onColumn(30_050L)          // mark B (slot [30000, 45000))
        val marks = tracker.markers()
        assertEquals(2, marks.size)
        assertEquals(318, marks[0].row)    // A shifted up once
        assertEquals(15_000L, marks[0].slotStartEpochMillis)
        assertEquals(319, marks[1].row)
        assertEquals(30_000L, marks[1].slotStartEpochMillis)
    }
}
