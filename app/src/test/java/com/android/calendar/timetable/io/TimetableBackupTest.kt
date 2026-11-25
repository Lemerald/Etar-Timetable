package com.android.calendar.timetable.io

import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.CourseTerm
import com.android.calendar.timetable.data.FreeDay
import com.android.calendar.timetable.data.OneTimeSlot
import com.android.calendar.timetable.data.RegularSlot
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.Term
import com.android.calendar.timetable.data.TimetableData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimetableBackupTest {

    private val sample = TimetableData(
        terms = listOf(Term(1, "WS 26/27", 20_740, 20_860)),
        courses = listOf(Course(5, "Algorithms", "Prof. X", "HS 1", 0xFF1454B9.toInt())),
        courseTerms = listOf(CourseTerm(5, 1)),
        regularSlots = listOf(RegularSlot(7, 5, 1, 1, 9 * 60, 10 * 60 + 30)),
        oneTimeSlots = listOf(OneTimeSlot(8, 5, 1, 20_800, 14 * 60, 16 * 60)),
        freeDays = listOf(FreeDay(9, 1, 20_810)),
        tasks = listOf(
            Task(id = 11, title = "Sheet 1", note = "ü \"quoted\"", courseId = 5, dueAtMillis = 123, createdAtMillis = 1),
            Task(id = 12, title = "Groceries", completed = true, completedAtMillis = 99, createdAtMillis = 2,
                calendarEventId = 777),
        ),
    )

    @Test
    fun roundTrip() {
        val decoded = TimetableBackup.decode(TimetableBackup.encode(sample))
        // Calendar event ids are device-specific and are not part of a backup.
        val expected = sample.copy(tasks = sample.tasks.map { it.copy(calendarEventId = null) })
        assertEquals(expected, decoded)
    }

    @Test
    fun rejectsOtherJson() {
        assertThrows(TimetableBackup.InvalidBackupException::class.java) { TimetableBackup.decode("{}") }
        assertThrows(TimetableBackup.InvalidBackupException::class.java) { TimetableBackup.decode("not json") }
    }

    @Test
    fun rejectsNewerVersion() {
        val json = TimetableBackup.encode(sample).replace("\"version\": 1", "\"version\": 99")
        assertThrows(TimetableBackup.InvalidBackupException::class.java) { TimetableBackup.decode(json) }
    }

    @Test
    fun rejectsDanglingReferences() {
        val broken = sample.copy(regularSlots = listOf(RegularSlot(7, 42, 1, 1, 0, 60)))
        assertThrows(TimetableBackup.InvalidBackupException::class.java) {
            TimetableBackup.decode(TimetableBackup.encode(broken))
        }
    }
}
