/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.ui.form

import androidx.fragment.app.FragmentManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date/time pickers and the formats the forms display. */
object FormPickers {

    private val DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    private val SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    private val DATE_THIS_YEAR = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

    /** "Thu, 26 Nov", with the year only when it is not the current one. */
    fun formatDate(date: LocalDate): String =
        date.format(if (date.year == LocalDate.now().year) DATE_THIS_YEAR else DATE)
    fun formatShortDate(date: LocalDate): String = date.format(SHORT_DATE)
    fun formatTime(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun pickDate(fm: FragmentManager, title: String, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .setSelection(initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            onPicked(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate())
        }
        picker.show(fm, "form_date")
    }

    fun pickTime(fm: FragmentManager, title: String, initialMinute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialMinute / 60)
            .setMinute(initialMinute % 60)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(fm, "form_time")
    }
}
