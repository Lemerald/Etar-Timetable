package com.android.calendar.timetable.reminders

import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.FreeDay
import com.android.calendar.timetable.data.OneTimeSlot
import com.android.calendar.timetable.data.RegularSlot
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TaskKind
import com.android.calendar.timetable.data.Term
import com.android.calendar.timetable.data.TimetableData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DueSummaryPlannerTest {

    private fun day(text: String) = LocalDate.parse(text).toEpochDay()
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // Mon 09:15-10:45 and Wed 11:00-12:30, Wed 2026-12-02 is a free day, one-off Fri 12-04 14:00-15:30.
    private val data = TimetableData(
        terms = listOf(Term(1, "WS", day("2026-11-02"), day("2026-12-18"))),
        courses = listOf(Course(1, "A"), Course(2, "B")),
        regularSlots = listOf(
            RegularSlot(1, 1, 1, 1, 9 * 60 + 15, 10 * 60 + 45),
            RegularSlot(2, 2, 1, 3, 11 * 60, 12 * 60 + 30),
            RegularSlot(3, 1, 1, 3, 8 * 60, 9 * 60),
        ),
        oneTimeSlots = listOf(OneTimeSlot(1, 2, 1, day("2026-12-04"), 14 * 60, 15 * 60 + 30)),
        freeDays = listOf(FreeDay(1, 1, day("2026-12-02"))),
    )

    @Test
    fun triggerAfterLastClassOrAfternoon() {
        assertEquals(LocalDateTime.parse("2026-11-30T10:55"), DueSummaryPlanner.triggerOn(data, LocalDate.parse("2026-11-30")))
        assertEquals(LocalDateTime.parse("2026-11-25T12:40"), DueSummaryPlanner.triggerOn(data, LocalDate.parse("2026-11-25")))
        // Free day, weekend and after term: no classes.
        assertEquals(LocalDateTime.parse("2026-12-02T15:00"), DueSummaryPlanner.triggerOn(data, LocalDate.parse("2026-12-02")))
        assertEquals(LocalDateTime.parse("2026-11-28T15:00"), DueSummaryPlanner.triggerOn(data, LocalDate.parse("2026-11-28")))
        // One-off slot.
        assertEquals(LocalDateTime.parse("2026-12-04T15:40"), DueSummaryPlanner.triggerOn(data, LocalDate.parse("2026-12-04")))
    }

    @Test
    fun nextTriggerRollsOverToTomorrow() {
        assertEquals(LocalDateTime.parse("2026-11-30T10:55"),
            DueSummaryPlanner.nextTrigger(data, LocalDateTime.parse("2026-11-30T08:00")))
        assertEquals(LocalDateTime.parse("2026-12-01T15:00"),
            DueSummaryPlanner.nextTrigger(data, LocalDateTime.parse("2026-11-30T10:55")))
    }

    @Test
    fun dueOnSkipsDoneAndOtherDays() {
        val now = at("2026-11-30T11:00")
        val tasks = listOf(
            Task(id = 1, title = "late", dueAtMillis = at("2026-12-01T18:00")),
            Task(id = 2, title = "early", dueAtMillis = at("2026-12-01T08:00")),
            Task(id = 3, title = "done", dueAtMillis = at("2026-12-01T09:00"), completed = true),
            Task(id = 4, title = "other day", dueAtMillis = at("2026-12-02T09:00")),
            Task(id = 5, title = "exam", kind = TaskKind.EXAM, dueAtMillis = at("2026-12-01T10:00"),
                endAtMillis = at("2026-12-01T11:00")),
            Task(id = 6, title = "no date"),
        )
        assertEquals(listOf("early", "exam", "late"),
            DueSummaryPlanner.dueOn(tasks, LocalDate.parse("2026-12-01"), now, zone).map { it.title })
    }
}
