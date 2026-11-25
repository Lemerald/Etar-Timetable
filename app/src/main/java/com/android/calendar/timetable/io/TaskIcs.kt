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
import com.android.calendar.timetable.data.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal RFC 5545 VTODO reader/writer for tasks. Pure Kotlin (no Android
 * dependencies) so it can be unit tested on the JVM.
 */
object TaskIcs {

    /** A VTODO as read from a file, before it is turned into a [Task]. */
    data class Todo(
        val uid: String?,
        val summary: String,
        val description: String,
        val dueMillis: Long?,
        val completed: Boolean,
        val completedMillis: Long?,
        val categories: List<String>,
    )

    private const val PRODID = "-//Etar-Timetable//Tasks//EN"
    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Time used for date-only DUE values: a task due "on a day" is due by the end of it. */
    val DATE_ONLY_DUE_TIME: LocalTime = LocalTime.of(23, 59)

    // ---- Writing -----------------------------------------------------------

    fun write(tasks: List<Task>, courses: List<Course>, nowMillis: Long = System.currentTimeMillis()): String {
        val courseNames = courses.associate { it.id to it.name }
        val out = StringBuilder()
        fun line(text: String) = out.append(fold(text)).append("\r\n")

        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:$PRODID")
        line("CALSCALE:GREGORIAN")
        for (task in tasks) {
            line("BEGIN:VTODO")
            line("UID:${uidFor(task)}")
            line("DTSTAMP:${utc(nowMillis)}")
            line("CREATED:${utc(task.createdAtMillis)}")
            line("SUMMARY:${escape(task.title)}")
            if (task.note.isNotEmpty()) line("DESCRIPTION:${escape(task.note)}")
            task.dueAtMillis?.let { line("DUE:${utc(it)}") }
            task.courseId?.let(courseNames::get)?.let { line("CATEGORIES:${escape(it)}") }
            if (task.completed) {
                line("STATUS:COMPLETED")
                line("PERCENT-COMPLETE:100")
                task.completedAtMillis?.let { line("COMPLETED:${utc(it)}") }
            } else {
                line("STATUS:NEEDS-ACTION")
            }
            line("END:VTODO")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    fun uidFor(task: Task) = "task-${task.id}-${task.createdAtMillis}@etar-timetable"

    private fun utc(millis: Long) = UTC_FORMAT.format(Instant.ofEpochMilli(millis))

    /** Escapes TEXT values (RFC 5545 3.3.11). */
    fun escape(text: String): String = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> {}
            else -> append(c)
        }
    }

    /** Folds a content line so that no line exceeds 75 octets (RFC 5545 3.1). */
    fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var octets = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            // Continuation lines start with a space, which counts toward the limit.
            if (octets + size > 75) {
                out.append("\r\n ")
                octets = 1
            }
            out.appendCodePoint(cp)
            octets += size
            i += chars
        }
        return out.toString()
    }

    // ---- Reading -----------------------------------------------------------

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Todo> =
        parseLines(text.split("\r\n", "\n", "\r"), zone)

    fun parseLines(rawLines: List<String>, zone: ZoneId = ZoneId.systemDefault()): List<Todo> {
        val todos = ArrayList<Todo>()
        var current: MutableMap<String, MutableList<Property>>? = null
        var nestedDepth = 0
        for (line in unfold(rawLines)) {
            val property = parseProperty(line) ?: continue
            val upperValue = property.value.uppercase()
            when {
                property.name == "BEGIN" && upperValue == "VTODO" && current == null -> {
                    current = HashMap()
                    nestedDepth = 0
                }
                current == null -> {}
                property.name == "BEGIN" -> nestedDepth++
                property.name == "END" && nestedDepth > 0 -> nestedDepth--
                property.name == "END" && upperValue == "VTODO" -> {
                    toTodo(current, zone)?.let(todos::add)
                    current = null
                }
                nestedDepth == 0 -> current.getOrPut(property.name) { ArrayList() } += property
            }
        }
        return todos
    }

    private class Property(val name: String, val params: Map<String, String>, val value: String)

    private fun unfold(lines: List<String>): List<String> {
        val result = ArrayList<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                result[result.size - 1] = result.last() + line.substring(1)
            } else if (line.isNotEmpty()) {
                result += line
            }
        }
        return result
    }

    private fun parseProperty(line: String): Property? {
        var inQuotes = false
        var colon = -1
        for ((index, c) in line.withIndex()) {
            if (c == '"') inQuotes = !inQuotes
            if (c == ':' && !inQuotes) { colon = index; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { param ->
            val eq = param.indexOf('=')
            if (eq <= 0) null else param.substring(0, eq).uppercase() to param.substring(eq + 1).trim('"')
        }.toMap()
        return Property(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun toTodo(props: Map<String, List<Property>>, zone: ZoneId): Todo? {
        fun first(name: String) = props[name]?.firstOrNull()
        val summary = first("SUMMARY")?.let { unescape(it.value) }.orEmpty().trim()
        val description = first("DESCRIPTION")?.let { unescape(it.value) }.orEmpty().trim()
        if (summary.isEmpty() && description.isEmpty()) return null
        val status = first("STATUS")?.value?.uppercase()
        val completedMillis = first("COMPLETED")?.let { parseDateTime(it, zone) }
        val percent = first("PERCENT-COMPLETE")?.value?.trim()?.toIntOrNull()
        return Todo(
            uid = first("UID")?.value,
            summary = summary,
            description = description,
            dueMillis = first("DUE")?.let { parseDateTime(it, zone) },
            completed = status == "COMPLETED" || completedMillis != null || percent == 100,
            completedMillis = completedMillis,
            categories = props["CATEGORIES"].orEmpty()
                .flatMap { splitList(it.value) }.map { unescape(it).trim() }.filter { it.isNotEmpty() },
        )
    }

    /** Parses DATE-TIME (UTC, floating, TZID) and DATE values. Returns null if unparseable. */
    private fun parseDateTime(property: Property, zone: ZoneId): Long? {
        val value = property.value.trim()
        return try {
            when {
                property.params["VALUE"].equals("DATE", ignoreCase = true) || value.length == 8 ->
                    LocalDate.parse(value, DATE_FORMAT).atTime(DATE_ONLY_DUE_TIME)
                        .atZone(zone).toInstant().toEpochMilli()
                value.endsWith("Z") ->
                    LocalDateTime.parse(value.dropLast(1), LOCAL_FORMAT)
                        .toInstant(ZoneOffset.UTC).toEpochMilli()
                else -> {
                    val tz = property.params["TZID"]?.let { id ->
                        runCatching { ZoneId.of(id) }.getOrNull()
                    } ?: zone
                    LocalDateTime.parse(value, LOCAL_FORMAT).atZone(tz).toInstant().toEpochMilli()
                }
            }
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }

    /** Splits a comma-separated value list, respecting escaped commas. */
    private fun splitList(value: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                current.append(c).append(value[i + 1]); i += 2; continue
            }
            if (c == ',') { parts += current.toString(); current.clear() } else current.append(c)
            i++
        }
        parts += current.toString()
        return parts
    }

    fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c); i++
            }
        }
    }
}
