/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.io

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.android.calendar.timetable.data.TimetableRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ws.xsoh.etar.R

/** Java-friendly entry point used by Etar's ImportActivity for VTODO components. */
object TaskImport {

    // ImportActivity finishes immediately (Theme.NoDisplay), so the import must outlive it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Parses VTODOs from the raw lines of an .ics file and imports them as tasks in
     * the background. Returns how many tasks were found (0 = nothing to import).
     */
    @JvmStatic
    fun importInBackground(context: Context, lines: List<String>): Int {
        val todos = TaskIcs.parseLines(lines)
        if (todos.isEmpty()) return 0
        val app = context.applicationContext
        scope.launch {
            try {
                val count = TimetableRepository.get(app).importTodos(todos)
                Toast.makeText(app, app.resources.getQuantityString(R.plurals.todo_imported_tasks, count, count),
                    Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("TaskImport", "Importing tasks failed", e)
                Toast.makeText(app, R.string.cal_import_error_msg, Toast.LENGTH_SHORT).show()
            }
        }
        return todos.size
    }
}
