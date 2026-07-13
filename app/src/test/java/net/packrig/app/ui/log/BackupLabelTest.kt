package net.packrig.app.ui.log

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupLabelTest {

    private val now = 1_700_000_000_000L

    @Test
    fun never_whenNoBackupTimestamp() {
        assertEquals("Last backup: never", lastBackupLabel(null, now))
    }

    @Test
    fun justNow_underOneMinute() {
        assertEquals("Last backup: just now", lastBackupLabel(now - 59_000, now))
    }

    @Test
    fun minutes_underOneHour() {
        assertEquals("Last backup: 5 min ago", lastBackupLabel(now - 5 * 60_000, now))
    }

    @Test
    fun hours_underOneDay() {
        assertEquals("Last backup: 3 h ago", lastBackupLabel(now - 3 * 3_600_000, now))
    }

    @Test
    fun days_overOneDay() {
        assertEquals("Last backup: 2 d ago", lastBackupLabel(now - 2 * 86_400_000, now))
    }
}
