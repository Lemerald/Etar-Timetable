package com.android.calendar.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ClassMeetingsTest {

    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    // Term: Mon 2026-11-02 .. Fri 2026-12-18. Algorithms Mon 09:15-10:45 and Wed 11:00-12:30.
    private val data = TimetableData(
        terms = listOf(Term(1, "WS", day("2026-11-02"), day("2026-12-18"))),
        courses = listOf(Course(1, "Algorithms"), Course(2, "Other")),
        courseTerms = listOf(CourseTerm(1, 1), CourseTerm(2, 1)),
        regularSlots = listOf(
            RegularSlot(1, 1, 1, 1, 9 * 60 + 15, 10 * 60 + 45),
            RegularSlot(2, 1, 1, 3, 11 * 60, 12 * 60 + 30),
            RegularSlot(3, 2, 1, 2, 8 * 60, 9 * 60),
        ),
        oneTimeSlots = listOf(OneTimeSlot(1, 1, 1, day("2026-12-05"), 14 * 60, 15 * 60)),
        freeDays = listOf(FreeDay(1, 1, day("2026-12-02"))),
    )

    @Test
    fun nextMeetingsSkipStartedClassFreeDaysAndOtherCourses() {
        // Monday 09:30: today's class already started, so the next one is Wednesday.
        val meetings = ClassMeetings.upcoming(data, 1, LocalDateTime.parse("2026-11-30T09:30"), limit = 4)
        assertEquals(
            listOf(
                // Wed 12-02 is a free day.
                LocalDateTime.parse("2026-12-05T14:00"), // one-off Saturday
                LocalDateTime.parse("2026-12-07T09:15"),
                LocalDateTime.parse("2026-12-09T11:00"),
                LocalDateTime.parse("2026-12-14T09:15"),
            ),
            meetings.map { it.start },
        )
        assertTrue(meetings.all { it.courseId == 1L })
    }

    @Test
    fun stopsAtTermEnd() {
        val meetings = ClassMeetings.upcoming(data, 1, LocalDateTime.parse("2026-12-15T00:00"), limit = 10)
        assertEquals(listOf(LocalDateTime.parse("2026-12-16T11:00")), meetings.map { it.start })
    }

    @Test
    fun courseDateRangeLimitsWeeklySlotsOnly() {
        val limited = data.copy(courses = listOf(
            Course(1, "Algorithms", startEpochDay = day("2026-12-08"), endEpochDay = day("2026-12-14")),
        ))
        val meetings = ClassMeetings.upcoming(limited, 1, LocalDateTime.parse("2026-11-30T00:00"), limit = 10)
        assertEquals(
            listOf(
                LocalDateTime.parse("2026-12-05T14:00"), // one-off: explicit, always counts
                LocalDateTime.parse("2026-12-09T11:00"),
                LocalDateTime.parse("2026-12-14T09:15"),
            ),
            meetings.map { it.start },
        )
    }

    @Test
    fun courseWithoutSlotsHasNoMeetings() {
        assertEquals(emptyList<ClassMeeting>(),
            ClassMeetings.upcoming(data.copy(oneTimeSlots = emptyList()), 3, LocalDateTime.parse("2026-11-01T00:00")))
    }

    @Test
    fun anchorsResolve() {
        val meeting = ClassMeeting(1, LocalDate.parse("2026-12-07"), 9 * 60 + 15, 10 * 60 + 45)
        assertEquals(LocalDateTime.parse("2026-12-07T09:14"), DueAnchor.BEFORE_CLASS.resolve(meeting))
        assertEquals(LocalDateTime.parse("2026-12-07T09:15"), DueAnchor.START_OF_CLASS.resolve(meeting))
        assertEquals(LocalDateTime.parse("2026-12-07T10:45"), DueAnchor.END_OF_CLASS.resolve(meeting))
        assertEquals(LocalDateTime.parse("2026-12-06T23:59"), DueAnchor.DAY_BEFORE.resolve(meeting))
    }
}
