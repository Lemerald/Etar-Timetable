package com.android.calendar.timetable.io

import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TaskIcsTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun millis(text: String, zone: ZoneId = berlin) =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun roundTripKeepsFields() {
        val course = Course(id = 3, name = "Algorithms, Part I")
        val tasks = listOf(
            Task(id = 1, title = "Sheet 3; ex. 1,2", note = "Line one\nback\\slash", courseId = 3,
                dueAtMillis = millis("2026-12-01T10:15"), createdAtMillis = 1000),
            Task(id = 2, title = "Done thing", completed = true,
                completedAtMillis = millis("2026-11-20T08:00"), createdAtMillis = 2000),
        )
        val parsed = TaskIcs.parse(TaskIcs.write(tasks, listOf(course), nowMillis = 0), berlin)

        assertEquals(2, parsed.size)
        val first = parsed[0]
        assertEquals("Sheet 3; ex. 1,2", first.summary)
        assertEquals("Line one\nback\\slash", first.description)
        assertEquals(millis("2026-12-01T10:15"), first.dueMillis)
        assertEquals(listOf("Algorithms, Part I"), first.categories)
        assertFalse(first.completed)
        assertEquals(TaskIcs.uidFor(tasks[0]), first.uid)

        val second = parsed[1]
        assertTrue(second.completed)
        assertNull(second.dueMillis)
        assertEquals(millis("2026-11-20T08:00"), second.completedMillis)
    }

    @Test
    fun outputUsesCrlfAndFoldsLongLines() {
        val longTitle = "ä".repeat(100) // 200 octets in UTF-8
        val text = TaskIcs.write(listOf(Task(id = 1, title = longTitle, createdAtMillis = 0)), emptyList(), 0)
        val lines = text.split("\r\n")
        lines.forEach { assertTrue("line too long: $it", it.toByteArray().size <= 75) }
        assertFalse(text.replace("\r\n", "").contains('\n'))
        assertEquals(longTitle, TaskIcs.parse(text, berlin).single().summary)
    }

    @Test
    fun parsesForeignDateFormsAndSkipsNestedAlarms() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:An event, not a task
            DTSTART:20261201T100000Z
            END:VEVENT
            BEGIN:VTODO
            SUMMARY:Date only
            DUE;VALUE=DATE:20261205
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:alarm text must not leak
            END:VALARM
            END:VTODO
            BEGIN:VTODO
            SUMMARY:With TZID
            DUE;TZID=America/New_York:20261205T090000
            PERCENT-COMPLETE:100
            END:VTODO
            BEGIN:VTODO
            SUMMARY:Floating and folded
              title
            DUE:20261205T090000
            CATEGORIES:A,B\,C
            END:VTODO
            BEGIN:VTODO
            DUE:20261205T090000
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val todos = TaskIcs.parse(ics, berlin)
        assertEquals(3, todos.size) // event ignored, empty VTODO skipped

        assertEquals("Date only", todos[0].summary)
        assertEquals("", todos[0].description)
        assertEquals(millis("2026-12-05T23:59"), todos[0].dueMillis)

        assertEquals(millis("2026-12-05T09:00", ZoneId.of("America/New_York")), todos[1].dueMillis)
        assertTrue(todos[1].completed)

        assertEquals("Floating and folded title", todos[2].summary)
        assertEquals(millis("2026-12-05T09:00"), todos[2].dueMillis)
        assertEquals(listOf("A", "B,C"), todos[2].categories)
    }

    @Test
    fun priorityAndExamTimes() {
        val tasks = listOf(
            Task(id = 1, title = "p", priority = com.android.calendar.timetable.data.TaskPriority.HIGH, createdAtMillis = 0),
            Task(id = 2, title = "exam", kind = com.android.calendar.timetable.data.TaskKind.EXAM,
                dueAtMillis = millis("2026-12-02T11:00"), endAtMillis = millis("2026-12-02T12:30"), createdAtMillis = 0),
        )
        val parsed = TaskIcs.parse(TaskIcs.write(tasks, emptyList(), 0), berlin)
        assertEquals(1, parsed[0].priority)
        assertEquals(millis("2026-12-02T11:00"), parsed[1].startMillis)
        assertEquals(millis("2026-12-02T12:30"), parsed[1].dueMillis)
    }

    @Test
    fun unparseableDueIsIgnored() {
        val todo = TaskIcs.parse("BEGIN:VTODO\nSUMMARY:x\nDUE:garbage\nEND:VTODO", berlin).single()
        assertNull(todo.dueMillis)
    }
}
