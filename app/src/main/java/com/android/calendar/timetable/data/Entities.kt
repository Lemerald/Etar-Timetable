/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.ZoneId

/**
 * A term (semester / school year). Dates are stored as epoch days so they are
 * independent of time zones and daylight saving changes.
 */
@Entity(tableName = "terms")
data class Term(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    /** Default university/campus of this term (class locations fall back to its address). */
    val campusId: Long? = null,
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endEpochDay)
}

/** A university / campus with an optional street address. */
@Entity(tableName = "campuses")
data class Campus(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val address: String = "",
)

/** A building of a [Campus]; an empty address means "use the campus address". */
@Entity(
    tableName = "buildings",
    indices = [Index("campusId")],
    foreignKeys = [ForeignKey(Campus::class, ["id"], ["campusId"], onDelete = ForeignKey.CASCADE)],
)
data class Building(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val campusId: Long,
    val name: String,
    @ColumnInfo(defaultValue = "") val address: String = "",
)

/** A course definition. Courses can be linked to multiple [Term]s. */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val color: Int = 0xFF1454B9.toInt(),
    /** Optional first/last day the course meets (e.g. a block course inside a term). */
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
) {
    fun isActiveOn(epochDay: Long): Boolean =
        (startEpochDay == null || epochDay >= startEpochDay) && (endEpochDay == null || epochDay <= endEpochDay)
}

/** Cross reference: which courses are active in which term. */
@Entity(
    tableName = "course_terms",
    primaryKeys = ["courseId", "termId"],
    indices = [Index("termId")],
    foreignKeys = [
        ForeignKey(Course::class, ["id"], ["courseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Term::class, ["id"], ["termId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CourseTerm(val courseId: Long, val termId: Long)

/** A recurring weekly class meeting. weekday uses ISO values (1 = Monday, 7 = Sunday). */
@Entity(
    tableName = "regular_slots",
    indices = [Index("courseId"), Index("termId")],
    foreignKeys = [
        ForeignKey(Course::class, ["id"], ["courseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Term::class, ["id"], ["termId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class RegularSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val termId: Long,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    /** Where the class meets; null building = campus of the term, null room = course room. */
    val buildingId: Long? = null,
    val room: String? = null,
)

/** A one-off class meeting (substituted / extra class). */
@Entity(
    tableName = "one_time_slots",
    indices = [Index("courseId"), Index("termId")],
    foreignKeys = [
        ForeignKey(Course::class, ["id"], ["courseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Term::class, ["id"], ["termId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class OneTimeSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val termId: Long,
    val dateEpochDay: Long,
    val startMinute: Int,
    val endMinute: Int,
    val buildingId: Long? = null,
    val room: String? = null,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
}

/** A holiday / free day within a term: no generated class events on this day. */
@Entity(
    tableName = "free_days",
    indices = [Index("termId", "dateEpochDay", unique = true)],
    foreignKeys = [ForeignKey(Term::class, ["id"], ["termId"], onDelete = ForeignKey.CASCADE)],
)
data class FreeDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val dateEpochDay: Long,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
}

/** A task / assignment. Tasks are local (Room) data; they are not generated events. */
@Entity(
    tableName = "tasks",
    indices = [Index("courseId")],
    foreignKeys = [ForeignKey(Course::class, ["id"], ["courseId"], onDelete = ForeignKey.SET_NULL)],
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val courseId: Long? = null,
    val dueAtMillis: Long? = null,
    val completed: Boolean = false,
    val completedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Event in the managed calendar that mirrors the due date, if any. */
    val calendarEventId: Long? = null,
    /** [TaskKind]: a task with a deadline, or an exam (a timed appointment). */
    @ColumnInfo(defaultValue = "0") val kind: Int = TaskKind.TASK,
    /** Exams: end of the exam; [dueAtMillis] is its start. */
    val endAtMillis: Long? = null,
    /** [TaskPriority]: 0 = none, 1 = low, 2 = medium, 3 = high. */
    @ColumnInfo(defaultValue = "0") val priority: Int = TaskPriority.NONE,
    /** Simplified RRULE (see [RepeatRule]); completing the task creates the next occurrence. */
    val repeatRule: String? = null,
    @ColumnInfo(defaultValue = "0") val modifiedAtMillis: Long = System.currentTimeMillis(),
    /** Row id of the mirrored task in an OpenTasks provider (DAVx5 bridge). */
    val remoteId: Long? = null,
    /** Provider LAST_MODIFIED of [remoteId] at the last sync. */
    val remoteModifiedAtMillis: Long? = null,
) {
    val isExam: Boolean get() = kind == TaskKind.EXAM
}

object TaskKind {
    const val TASK = 0
    const val EXAM = 1
}

object TaskPriority {
    const val NONE = 0
    const val LOW = 1
    const val MEDIUM = 2
    const val HIGH = 3
}

/** A checklist entry (sub-step) of a task. */
@Entity(
    tableName = "checklist_items",
    indices = [Index("taskId")],
    foreignKeys = [ForeignKey(Task::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
)
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val text: String,
    val done: Boolean = false,
    val position: Int = 0,
)

/**
 * Bookkeeping for events materialized from slots into the CalendarProvider.
 * [fingerprint] is the exact data the generator wrote; if the event no longer
 * matches it, the user edited the event and regeneration must not throw it away.
 */
@Entity(
    tableName = "generated_events",
    indices = [Index("eventKey", unique = true), Index("termId")],
    foreignKeys = [ForeignKey(Term::class, ["id"], ["termId"], onDelete = ForeignKey.CASCADE)],
)
data class GeneratedEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val eventKey: String,
    val eventId: Long,
    val calendarId: Long,
    val fingerprint: String,
    val color: Int = 0,
    val userEdited: Boolean = false,
)

/** All timetable data needed by the timetable view, loaded together. */
data class TimetableData(
    val terms: List<Term> = emptyList(),
    val courses: List<Course> = emptyList(),
    val courseTerms: List<CourseTerm> = emptyList(),
    val regularSlots: List<RegularSlot> = emptyList(),
    val oneTimeSlots: List<OneTimeSlot> = emptyList(),
    val freeDays: List<FreeDay> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val checklistItems: List<ChecklistItem> = emptyList(),
    val campuses: List<Campus> = emptyList(),
    val buildings: List<Building> = emptyList(),
) {
    /** Where a slot of [courseId] in [termId] takes place (see [SlotLocations]). */
    fun placeOf(termId: Long, courseId: Long, buildingId: Long?, room: String?): SlotPlace =
        SlotLocations.resolve(buildingId, room, courseById(courseId), terms.firstOrNull { it.id == termId },
            campuses.associateBy { it.id }, buildings.associateBy { it.id })

    fun checklistFor(taskId: Long): List<ChecklistItem> =
        checklistItems.filter { it.taskId == taskId }.sortedBy { it.position }

    fun coursesForTerm(termId: Long): List<Course> {
        val ids = courseTerms.filter { it.termId == termId }.map { it.courseId }.toSet()
        return courses.filter { it.id in ids }
    }

    fun courseById(id: Long): Course? = courses.firstOrNull { it.id == id }
}

fun minutesToTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

fun epochDayToMillis(epochDay: Long, minuteOfDay: Int): Long =
    LocalDate.ofEpochDay(epochDay)
        .atTime(minuteOfDay / 60, minuteOfDay % 60)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
