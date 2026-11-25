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
import java.time.format.DateTimeFormatter

/**
 * Repetition of a task, stored as a small RFC 5545 RRULE subset
 * (FREQ=DAILY|WEEKLY|MONTHLY, INTERVAL, optional UNTIL date). Repeats are counted
 * from the due date: completing an occurrence creates the next one.
 */
data class RepeatRule(val frequency: Frequency, val interval: Int = 1, val until: LocalDate? = null) {

    enum class Frequency { DAILY, WEEKLY, MONTHLY }

    init {
        require(interval >= 1) { "interval must be positive" }
    }

    /** The next due date-time after [due], or null when the rule has ended. */
    fun next(due: LocalDateTime): LocalDateTime? {
        val next = when (frequency) {
            Frequency.DAILY -> due.plusDays(interval.toLong())
            Frequency.WEEKLY -> due.plusWeeks(interval.toLong())
            Frequency.MONTHLY -> due.plusMonths(interval.toLong())
        }
        return next.takeIf { until == null || !next.toLocalDate().isAfter(until) }
    }

    fun format(): String = buildString {
        append("FREQ=").append(frequency.name).append(";INTERVAL=").append(interval)
        until?.let { append(";UNTIL=").append(it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
    }

    companion object {
        /** Parses the subset written by [format]; returns null for anything else. */
        fun parse(text: String?): RepeatRule? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(';').mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim().uppercase() to it.substring(eq + 1).trim()
            }.toMap()
            val frequency = parts["FREQ"]?.uppercase()
                ?.let { name -> Frequency.values().firstOrNull { it.name == name } } ?: return null
            val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
            if (interval < 1) return null
            val until = parts["UNTIL"]?.let {
                runCatching { LocalDate.parse(it.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            }
            return RepeatRule(frequency, interval, until)
        }
    }
}
