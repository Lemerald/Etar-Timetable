/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.io

import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.CourseTerm
import com.android.calendar.timetable.data.FreeDay
import com.android.calendar.timetable.data.OneTimeSlot
import com.android.calendar.timetable.data.RegularSlot
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.Term
import com.android.calendar.timetable.data.TimetableData
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * App-specific JSON backup of all timetable data (terms, courses, slots, free
 * days, tasks). Generated calendar events are not included: they are rebuilt
 * from the restored data.
 */
object TimetableBackup {

    const val FORMAT = "etar-timetable-backup"
    const val VERSION = 1

    class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encode(data: TimetableData, exportedAtMillis: Long = System.currentTimeMillis()): String =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", exportedAtMillis)
            put("terms", JSONArray(data.terms.map {
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("startEpochDay", it.startEpochDay).put("endEpochDay", it.endEpochDay)
            }))
            put("courses", JSONArray(data.courses.map {
                JSONObject().put("id", it.id).put("name", it.name).put("teacher", it.teacher)
                    .put("room", it.room).put("color", it.color)
            }))
            put("courseTerms", JSONArray(data.courseTerms.map {
                JSONObject().put("courseId", it.courseId).put("termId", it.termId)
            }))
            put("regularSlots", JSONArray(data.regularSlots.map {
                JSONObject().put("id", it.id).put("courseId", it.courseId).put("termId", it.termId)
                    .put("weekday", it.weekday).put("startMinute", it.startMinute).put("endMinute", it.endMinute)
            }))
            put("oneTimeSlots", JSONArray(data.oneTimeSlots.map {
                JSONObject().put("id", it.id).put("courseId", it.courseId).put("termId", it.termId)
                    .put("dateEpochDay", it.dateEpochDay)
                    .put("startMinute", it.startMinute).put("endMinute", it.endMinute)
            }))
            put("freeDays", JSONArray(data.freeDays.map {
                JSONObject().put("id", it.id).put("termId", it.termId).put("dateEpochDay", it.dateEpochDay)
            }))
            put("tasks", JSONArray(data.tasks.map {
                JSONObject().put("id", it.id).put("title", it.title).put("note", it.note)
                    .putOpt("courseId", it.courseId).putOpt("dueAtMillis", it.dueAtMillis)
                    .put("completed", it.completed).putOpt("completedAtMillis", it.completedAtMillis)
                    .put("createdAtMillis", it.createdAtMillis)
            }))
        }.toString(2)

    /** Parses and validates a backup. Throws [InvalidBackupException] on any problem. */
    fun decode(json: String): TimetableData {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw InvalidBackupException("Not a JSON file", e)
        }
        if (root.optString("format") != FORMAT) throw InvalidBackupException("Not a timetable backup")
        val version = root.optInt("version", -1)
        if (version < 1 || version > VERSION) throw InvalidBackupException("Unsupported backup version $version")

        val data = try {
            TimetableData(
                terms = root.objects("terms").map {
                    Term(it.getLong("id"), it.getString("name"), it.getLong("startEpochDay"), it.getLong("endEpochDay"))
                },
                courses = root.objects("courses").map {
                    Course(it.getLong("id"), it.getString("name"), it.optString("teacher"),
                        it.optString("room"), it.getInt("color"))
                },
                courseTerms = root.objects("courseTerms").map {
                    CourseTerm(it.getLong("courseId"), it.getLong("termId"))
                },
                regularSlots = root.objects("regularSlots").map {
                    RegularSlot(it.getLong("id"), it.getLong("courseId"), it.getLong("termId"),
                        it.getInt("weekday"), it.getInt("startMinute"), it.getInt("endMinute"))
                },
                oneTimeSlots = root.objects("oneTimeSlots").map {
                    OneTimeSlot(it.getLong("id"), it.getLong("courseId"), it.getLong("termId"),
                        it.getLong("dateEpochDay"), it.getInt("startMinute"), it.getInt("endMinute"))
                },
                freeDays = root.objects("freeDays").map {
                    FreeDay(it.getLong("id"), it.getLong("termId"), it.getLong("dateEpochDay"))
                },
                tasks = root.objects("tasks").map {
                    Task(
                        id = it.getLong("id"),
                        title = it.getString("title"),
                        note = it.optString("note"),
                        courseId = it.optLongOrNull("courseId"),
                        dueAtMillis = it.optLongOrNull("dueAtMillis"),
                        completed = it.optBoolean("completed"),
                        completedAtMillis = it.optLongOrNull("completedAtMillis"),
                        createdAtMillis = it.optLong("createdAtMillis", System.currentTimeMillis()),
                    )
                },
            )
        } catch (e: JSONException) {
            throw InvalidBackupException("Backup is missing data: ${e.message}", e)
        }
        validate(data)
        return data
    }

    private fun validate(data: TimetableData) {
        val termIds = data.terms.map { it.id }.toSet()
        val courseIds = data.courses.map { it.id }.toSet()
        fun check(ok: Boolean, what: String) { if (!ok) throw InvalidBackupException("Backup references a missing $what") }
        data.courseTerms.forEach { check(it.courseId in courseIds && it.termId in termIds, "course or term") }
        data.regularSlots.forEach { check(it.courseId in courseIds && it.termId in termIds, "course or term") }
        data.oneTimeSlots.forEach { check(it.courseId in courseIds && it.termId in termIds, "course or term") }
        data.freeDays.forEach { check(it.termId in termIds, "term") }
        data.tasks.forEach { task -> task.courseId?.let { check(it in courseIds, "course") } }
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
}
