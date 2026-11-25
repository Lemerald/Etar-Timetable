package com.android.calendar.timetable.io

import com.android.calendar.timetable.data.Building
import com.android.calendar.timetable.data.Campus
import com.android.calendar.timetable.data.ChecklistItem
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.CourseTerm
import com.android.calendar.timetable.data.FreeDay
import com.android.calendar.timetable.data.OneTimeSlot
import com.android.calendar.timetable.data.RegularSlot
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TaskKind
import com.android.calendar.timetable.data.TaskPriority
import com.android.calendar.timetable.data.Term
import com.android.calendar.timetable.data.TimetableData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimetableBackupTest {

    private val sample = TimetableData(
        terms = listOf(Term(1, "WS 26/27", 20_740, 20_860, campusId = 3)),
        campuses = listOf(Campus(3, "MIT", "77 Massachusetts Ave")),
        buildings = listOf(Building(4, 3, "Building A", "32 Vassar St"), Building(5, 3, "B", "")),
        courses = listOf(Course(5, "Algorithms", "Prof. X", "HS 1", 0xFF1454B9.toInt(), 20_750, 20_800)),
        courseTerms = listOf(CourseTerm(5, 1)),
        regularSlots = listOf(RegularSlot(7, 5, 1, 1, 9 * 60, 10 * 60 + 30, buildingId = 4, room = "1.5")),
        oneTimeSlots = listOf(OneTimeSlot(8, 5, 1, 20_800, 14 * 60, 16 * 60)),
        freeDays = listOf(FreeDay(9, 1, 20_810)),
        tasks = listOf(
            Task(id = 11, title = "Sheet 1", note = "ü \"quoted\"", courseId = 5, dueAtMillis = 123, createdAtMillis = 1,
                priority = TaskPriority.HIGH, repeatRule = "FREQ=WEEKLY;INTERVAL=1", modifiedAtMillis = 5),
            Task(id = 13, title = "Exam", kind = TaskKind.EXAM, dueAtMillis = 1000, endAtMillis = 2000,
                createdAtMillis = 3, modifiedAtMillis = 6),
            Task(id = 12, title = "Groceries", completed = true, completedAtMillis = 99, createdAtMillis = 2,
                calendarEventId = 777, remoteId = 4, remoteModifiedAtMillis = 8, modifiedAtMillis = 7),
        ),
        checklistItems = listOf(ChecklistItem(1, 11, "Part a", true, 0), ChecklistItem(2, 11, "Part b", false, 1)),
    )

    @Test
    fun roundTrip() {
        val decoded = TimetableBackup.decode(TimetableBackup.encode(sample))
        // Calendar event ids and sync links are device-specific and are not part of a backup.
        val expected = sample.copy(tasks = sample.tasks.map {
            it.copy(calendarEventId = null, remoteId = null, remoteModifiedAtMillis = null)
        })
        assertEquals(expected, decoded)
    }

    @Test
    fun rejectsOtherJson() {
        assertThrows(TimetableBackup.InvalidBackupException::class.java) { TimetableBackup.decode("{}") }
        assertThrows(TimetableBackup.InvalidBackupException::class.java) { TimetableBackup.decode("not json") }
    }

    @Test
    fun rejectsNewerVersion() {
        val json = TimetableBackup.encode(sample).replace("\"version\": 3", "\"version\": 99")
        assertThrows(TimetableBackup.InvalidBackupException::class.java) { TimetableBackup.decode(json) }
    }

    @Test
    fun rejectsDanglingBuilding() {
        val broken = sample.copy(regularSlots = listOf(RegularSlot(7, 5, 1, 1, 0, 60, buildingId = 42)))
        assertThrows(TimetableBackup.InvalidBackupException::class.java) {
            TimetableBackup.decode(TimetableBackup.encode(broken))
        }
    }

    @Test
    fun rejectsDanglingReferences() {
        val broken = sample.copy(regularSlots = listOf(RegularSlot(7, 42, 1, 1, 0, 60)))
        assertThrows(TimetableBackup.InvalidBackupException::class.java) {
            TimetableBackup.decode(TimetableBackup.encode(broken))
        }
    }

    @Test
    fun readsVersion1() {
        val v1 = """{"format":"etar-timetable-backup","version":1,"terms":[],"courses":[
            {"id":1,"name":"A","teacher":"","room":"","color":5}],
            "tasks":[{"id":2,"title":"T","note":"","courseId":1,"dueAtMillis":9,"completed":false,"createdAtMillis":1}]}"""
        val data = TimetableBackup.decode(v1)
        assertEquals(null, data.courses.single().startEpochDay)
        val task = data.tasks.single()
        assertEquals(TaskKind.TASK, task.kind)
        assertEquals(TaskPriority.NONE, task.priority)
        assertEquals(emptyList<ChecklistItem>(), data.checklistItems)
    }
}
