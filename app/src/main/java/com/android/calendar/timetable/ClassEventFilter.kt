/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable

import android.content.Context
import android.provider.CalendarContract.Instances
import com.android.calendar.timetable.data.TimetableDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * How the calendar views (day, week, month, agenda) show generated class events:
 * optionally hidden, and otherwise without their location (it is long and the
 * same every week; the event details still show it). Other events are untouched.
 *
 * Class events are recognised by id from the timetable's bookkeeping, kept in an
 * in-memory set so the views can check it synchronously.
 */
object ClassEventFilter {

    private const val PREFS = "etar_timetable_prefs"
    private const val KEY_SHOW_CLASSES = "show_classes_in_calendar"

    @Volatile
    private var classEventIds: Set<Long> = emptySet()
    @Volatile
    private var showClasses = true

    /** Called at app start; keeps the id set in sync with the database. */
    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        showClasses = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_CLASSES, true)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            TimetableDatabase.get(app).timetableDao().observeGeneratedEventIds().collect { classEventIds = it.toSet() }
        }
    }

    @JvmStatic
    fun showClasses(): Boolean = showClasses

    @JvmStatic
    fun setShowClasses(context: Context, show: Boolean) {
        showClasses = show
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_CLASSES, show).apply()
    }

    @JvmStatic
    fun isClassEvent(eventId: Long): Boolean = eventId in classEventIds

    /** True if the views should leave this event out. */
    @JvmStatic
    fun isHidden(eventId: Long): Boolean = !showClasses && isClassEvent(eventId)

    /** Extra SQL for Instances queries that hides class events, or "" when they are shown. */
    @JvmStatic
    fun instancesExclusion(): String {
        val ids = classEventIds
        if (showClasses || ids.isEmpty()) return ""
        return " AND ${Instances.EVENT_ID} NOT IN (${ids.joinToString(",")})"
    }
}
