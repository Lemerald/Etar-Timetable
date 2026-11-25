/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One concrete meeting of a course on a date (from a weekly or one-off slot). */
data class ClassMeeting(
    val courseId: Long,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
) {
    val start: LocalDateTime get() = date.atTime(startMinute / 60, startMinute % 60)
    val end: LocalDateTime get() = date.atTime(endMinute / 60, endMinute % 60)
}

/** Due-time presets relative to a class meeting (US-2.5, modelled on Power Planner). */
enum class DueAnchor {
    /** One minute before the class starts (hand in before class). */
    BEFORE_CLASS,
    START_OF_CLASS,
    END_OF_CLASS,
    /** End of the day before the class. */
    DAY_BEFORE;

    fun resolve(meeting: ClassMeeting): LocalDateTime = when (this) {
        BEFORE_CLASS -> meeting.start.minusMinutes(1)
        START_OF_CLASS -> meeting.start
        END_OF_CLASS -> meeting.end
        DAY_BEFORE -> meeting.date.minusDays(1).atTime(END_OF_DAY)
    }

    companion object {
        val END_OF_DAY: LocalTime = LocalTime.of(23, 59)
    }
}

object ClassMeetings {

    /**
     * The next [limit] meetings of [courseId] that start after [after], following the
     * same rules as schedule generation: weekly slots repeat inside their term, one-off
     * slots count when inside their term, free days of the term cancel meetings, and
     * weekly slots respect the course's own date range.
     */
    fun upcoming(
        data: TimetableData,
        courseId: Long,
        after: LocalDateTime,
        limit: Int = 10,
        horizonDays: Long = 400,
    ): List<ClassMeeting> {
        val termsById = data.terms.associateBy { it.id }
        val freeDaysByTerm = data.freeDays.groupBy({ it.termId }, { it.dateEpochDay }).mapValues { it.value.toSet() }
        fun isClassDay(termId: Long, epochDay: Long): Boolean {
            val term = termsById[termId] ?: return false
            return epochDay in term.startEpochDay..term.endEpochDay &&
                epochDay !in freeDaysByTerm[termId].orEmpty()
        }

        val regular = data.regularSlots.filter { it.courseId == courseId }
        val course = data.courseById(courseId)
        val result = sortedSetOf(compareBy<ClassMeeting>({ it.start }, { it.end }))

        data.oneTimeSlots.filter { it.courseId == courseId && isClassDay(it.termId, it.dateEpochDay) }
            .forEach { result += ClassMeeting(courseId, it.date, it.startMinute, it.endMinute) }

        if (regular.isNotEmpty()) {
            val lastDay = after.toLocalDate().plusDays(horizonDays)
            var day = after.toLocalDate()
            // Stop once enough weekly meetings were found; one-offs are all known already.
            var weeklyFound = 0
            while (!day.isAfter(lastDay) && weeklyFound < limit) {
                val epochDay = day.toEpochDay()
                for (slot in regular) {
                    if (slot.weekday == day.dayOfWeek.value && isClassDay(slot.termId, epochDay) &&
                        course?.isActiveOn(epochDay) != false
                    ) {
                        val meeting = ClassMeeting(courseId, day, slot.startMinute, slot.endMinute)
                        result += meeting
                        if (meeting.start.isAfter(after)) weeklyFound++
                    }
                }
                day = day.plusDays(1)
            }
        }
        return result.filter { it.start.isAfter(after) }.take(limit)
    }

    /** All meetings (any course) on [date], sorted by start. */
    fun onDate(data: TimetableData, date: LocalDate): List<ClassMeeting> {
        val epochDay = date.toEpochDay()
        val termsById = data.terms.associateBy { it.id }
        val freeDays = data.freeDays.filter { it.dateEpochDay == epochDay }.map { it.termId }.toSet()
        fun isClassDay(termId: Long): Boolean {
            val term = termsById[termId] ?: return false
            return epochDay in term.startEpochDay..term.endEpochDay && termId !in freeDays
        }
        val weekly = data.regularSlots.filter {
            it.weekday == date.dayOfWeek.value && isClassDay(it.termId) &&
                data.courseById(it.courseId)?.isActiveOn(epochDay) != false
        }.map { ClassMeeting(it.courseId, date, it.startMinute, it.endMinute) }
        val oneOff = data.oneTimeSlots.filter { it.dateEpochDay == epochDay && isClassDay(it.termId) }
            .map { ClassMeeting(it.courseId, date, it.startMinute, it.endMinute) }
        return (weekly + oneOff).distinct().sortedBy { it.start }
    }
}
