/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.reminders

import com.android.calendar.timetable.data.ClassMeetings
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.TodoBuckets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * When to post the "due tomorrow" summary, modelled on Power Planner: 10 minutes
 * after the day's last class, or at 15:00 on days without classes.
 */
object DueSummaryPlanner {

    val NO_CLASS_TIME: LocalTime = LocalTime.of(15, 0)
    const val MINUTES_AFTER_LAST_CLASS = 10L

    fun triggerOn(data: TimetableData, date: LocalDate): LocalDateTime =
        ClassMeetings.onDate(data, date).maxOfOrNull { it.end }?.plusMinutes(MINUTES_AFTER_LAST_CLASS)
            ?: date.atTime(NO_CLASS_TIME)

    /** The next trigger strictly after [now] (today's if still ahead, else tomorrow's). */
    fun nextTrigger(data: TimetableData, now: LocalDateTime): LocalDateTime {
        val today = triggerOn(data, now.toLocalDate())
        return if (today.isAfter(now)) today else triggerOn(data, now.toLocalDate().plusDays(1))
    }

    /** Open tasks and upcoming exams due on [date], in due order. */
    fun dueOn(tasks: List<Task>, date: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Task> =
        tasks.filter { task ->
            val due = task.dueAtMillis ?: return@filter false
            !TodoBuckets.isDone(task, now) && Instant.ofEpochMilli(due).atZone(zone).toLocalDate() == date
        }.sortedBy { it.dueAtMillis }
}
