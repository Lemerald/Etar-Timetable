/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Due-date groups of the To-dos view (US-2.4, plus Overdue like Power Planner). */
enum class TodoBucket { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER, NO_DATE }

object TodoBuckets {

    /** Completed tasks, and exams that are over, count as done. */
    fun isDone(task: Task, now: Long): Boolean =
        task.completed || (task.isExam && (task.endAtMillis ?: task.dueAtMillis ?: Long.MAX_VALUE) < now)

    fun bucketOf(task: Task, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): TodoBucket {
        val due = task.dueAtMillis ?: return TodoBucket.NO_DATE
        val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
        val today = now.toLocalDate()
        val date = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
        return when {
            // Done items stay in their original date group.
            due < nowMillis && !isDone(task, nowMillis) && !task.isExam -> TodoBucket.OVERDUE
            !date.isAfter(today) -> TodoBucket.TODAY
            date == today.plusDays(1) -> TodoBucket.TOMORROW
            !date.isAfter(today.with(DayOfWeek.SUNDAY)) -> TodoBucket.THIS_WEEK
            else -> TodoBucket.LATER
        }
    }

    /** Groups [tasks] in bucket order; inside a group by due time, then higher priority first. */
    fun group(
        tasks: List<Task>,
        now: LocalDateTime,
        showDone: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<TodoBucket, List<Task>> {
        val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
        return tasks.filter { showDone || !isDone(it, nowMillis) }
            .groupBy { bucketOf(it, now, zone) }
            .mapValues { (_, list) ->
                list.sortedWith(compareBy<Task>({ isDone(it, nowMillis) }, { it.dueAtMillis ?: Long.MAX_VALUE },
                    { -it.priority }, { it.title }))
            }
            .toSortedMap()
    }
}
