package com.android.calendar.timetable.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupRetentionTest {

    @Test
    fun fileNameIsRecognised() {
        val name = BackupRetention.fileName(LocalDate.parse("2026-11-26"))
        assertEquals("timetable-backup-2026-11-26.json", name)
        assertTrue(BackupRetention.isBackupName(name))
        assertTrue(BackupRetention.isBackupName("timetable-backup-2026-11-26 (1).json"))
        assertFalse(BackupRetention.isBackupName("timetable-backup-2026-11-26-old.json"))
        assertFalse(BackupRetention.isBackupName("notes.json"))
    }

    @Test
    fun keepsNewestAndNeverTouchesOtherFiles() {
        val names = (1..20).map { BackupRetention.fileName(LocalDate.parse("2026-11-01").plusDays(it.toLong())) } +
            listOf("holiday-photos.jpg", "timetable-backup-manual.json")
        val deleted = BackupRetention.toDelete(names.shuffled(), keep = 14)
        assertEquals((1..6).map { BackupRetention.fileName(LocalDate.parse("2026-11-01").plusDays(it.toLong())) },
            deleted.sorted())
    }

    @Test
    fun renamedDuplicatesArePrunedFirst() {
        val names = listOf("timetable-backup-2026-11-26 (1).json", "timetable-backup-2026-11-26.json",
            "timetable-backup-2026-11-25.json")
        assertEquals(listOf("timetable-backup-2026-11-25.json"), BackupRetention.toDelete(names, keep = 2))
        assertEquals(listOf("timetable-backup-2026-11-26 (1).json", "timetable-backup-2026-11-25.json"),
            BackupRetention.toDelete(names, keep = 1))
    }

    @Test
    fun nothingToDeleteBelowLimit() {
        assertEquals(emptyList<String>(), BackupRetention.toDelete(listOf("timetable-backup-2026-11-26.json"), 14))
    }
}
