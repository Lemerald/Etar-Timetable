package com.android.calendar.timetable.io

import com.android.calendar.timetable.data.TaskKind
import com.android.calendar.timetable.data.TaskPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backups written by every released format version must still restore: a user may
 * restore a months-old backup (or an automatic one) into a newer app.
 *
 * Each version has a sample file in src/test/resources/backups/. When the format
 * changes, bump [TimetableBackup.VERSION], add `v<N>.json` and a test below;
 * [everyVersionHasASampleFile] fails until the sample exists.
 */
class BackupCompatibilityTest {

    private fun sample(version: Int): String =
        checkNotNull(javaClass.getResource("/backups/v$version.json")) { "missing backups/v$version.json" }.readText()

    private fun decode(version: Int) = TimetableBackup.decode(sample(version))

    @Test
    fun everyVersionHasASampleFile() {
        for (version in 1..TimetableBackup.VERSION) {
            assertNotNull("backups/v$version.json", javaClass.getResource("/backups/v$version.json"))
        }
    }

    @Test
    fun everySampleSurvivesARoundTripThroughTheCurrentFormat() {
        for (version in 1..TimetableBackup.VERSION) {
            val data = decode(version)
            assertEquals("v$version", data, TimetableBackup.decode(TimetableBackup.encode(data)))
        }
    }

    @Test
    fun version1() {
        val data = decode(1)
        assertEquals("Winter term", data.terms.single().name)
        val course = data.courses.single()
        assertNull(course.startEpochDay)
        assertNull(course.buildingId)
        assertEquals(1, data.freeDays.size)
        val task = data.tasks.single()
        assertEquals(TaskKind.TASK, task.kind)
        assertEquals(TaskPriority.NONE, task.priority)
        assertTrue(data.checklistItems.isEmpty())
        assertTrue(data.campuses.isEmpty())
    }

    @Test
    fun version2() {
        val data = decode(2)
        assertEquals(20750L, data.courses.single().startEpochDay)
        val (sheet, exam) = data.tasks.sortedBy { it.id }
        assertEquals(TaskPriority.HIGH, sheet.priority)
        assertEquals("FREQ=WEEKLY;INTERVAL=1", sheet.repeatRule)
        assertEquals(TaskKind.EXAM, exam.kind)
        assertEquals(1765005400000L, exam.endAtMillis)
        assertEquals(listOf("Part a", "Part b"), data.checklistFor(11).map { it.text })
        assertNull(data.terms.single().campusId)
        assertNull(data.regularSlots.single().buildingId)
    }

    @Test
    fun version3() {
        val data = decode(3)
        assertEquals(3L, data.terms.single().campusId)
        assertEquals("77 Massachusetts Ave, Cambridge", data.campuses.single().address)
        val slot = data.regularSlots.single()
        assertEquals(4L, slot.buildingId)
        assertEquals("1.5", slot.room)
        assertNull(data.courses.single().buildingId)
        assertEquals("Building A, 1.5, 32 Vassar St",
            data.placeOf(1, 5, slot.buildingId, slot.room).eventLocation)
    }

    @Test
    fun version4() {
        val data = decode(4)
        assertEquals(4L, data.courses.single().buildingId)
        // The slot has no building of its own: it takes the course's.
        assertEquals("Building A, HS 1, 32 Vassar St", data.placeOf(1, 5, null, null).eventLocation)
    }
}
