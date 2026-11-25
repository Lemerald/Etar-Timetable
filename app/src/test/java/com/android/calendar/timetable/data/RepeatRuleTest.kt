package com.android.calendar.timetable.data

import com.android.calendar.timetable.data.RepeatRule.Frequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RepeatRuleTest {

    private val due = LocalDateTime.parse("2026-11-30T09:14")

    @Test
    fun next() {
        assertEquals(LocalDateTime.parse("2026-12-01T09:14"), RepeatRule(Frequency.DAILY).next(due))
        assertEquals(LocalDateTime.parse("2026-12-14T09:14"), RepeatRule(Frequency.WEEKLY, 2).next(due))
        assertEquals(LocalDateTime.parse("2027-01-31T10:00"),
            RepeatRule(Frequency.MONTHLY).next(LocalDateTime.parse("2026-12-31T10:00")))
    }

    @Test
    fun stopsAfterUntil() {
        val rule = RepeatRule(Frequency.WEEKLY, until = LocalDate.parse("2026-12-07"))
        assertEquals(LocalDateTime.parse("2026-12-07T09:14"), rule.next(due))
        assertNull(rule.next(LocalDateTime.parse("2026-12-07T09:14")))
    }

    @Test
    fun formatAndParse() {
        val rule = RepeatRule(Frequency.WEEKLY, 2, LocalDate.parse("2027-02-12"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;UNTIL=20270212", rule.format())
        assertEquals(rule, RepeatRule.parse(rule.format()))
        // Foreign RRULEs: UNTIL as date-time, missing INTERVAL.
        assertEquals(RepeatRule(Frequency.DAILY, 1, LocalDate.parse("2027-01-01")),
            RepeatRule.parse("FREQ=DAILY;UNTIL=20270101T235959Z"))
        assertNull(RepeatRule.parse("FREQ=YEARLY"))
        assertNull(RepeatRule.parse(""))
        assertNull(RepeatRule.parse("FREQ=WEEKLY;INTERVAL=0"))
    }
}
