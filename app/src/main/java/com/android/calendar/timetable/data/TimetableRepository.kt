package com.android.calendar.timetable.data

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Reminders
import androidx.room.withTransaction
import com.android.calendar.persistence.CalendarRepository
import com.android.calendar.timetable.io.TaskIcs
import com.android.calendar.timetable.reminders.DueSummary
import com.android.calendar.timetable.reminders.ReminderSettings
import com.android.calendar.timetable.sync.OpenTasksSync
import com.android.calendar.timetable.sync.TaskSyncEngine
import com.android.calendar.timetable.io.TimetableBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import ws.xsoh.etar.R

/** One event the generator wants in the calendar. */
data class PlannedEvent(
    val key: String,
    val slotType: String,
    val slotId: Long,
    val termId: Long,
    val epochDay: Long,
    val title: String,
    val location: String,
    val description: String,
    val color: Int,
    val startMillis: Long,
    val endMillis: Long,
) {
    // The colour is tracked separately (in GeneratedEvent) because CalendarProvider
    // may normalise EVENT_COLOR into a colour key on write.
    val fingerprint: String
        get() = listOf(title, location, description, startMillis, endMillis)
            .joinToString("\u001f")
}

/** Result of comparing the desired schedule with what is currently in the calendar. */
data class GenerationPreview(
    val termId: Long,
    val toCreate: List<PlannedEvent> = emptyList(),
    val toUpdate: List<PlannedEvent> = emptyList(),
    val toDelete: List<GeneratedEvent> = emptyList(),
    val stale: List<GeneratedEvent> = emptyList(),
    val userEditedKept: List<GeneratedEvent> = emptyList(),
    val details: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = toCreate.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty() && stale.isEmpty()
}

class TimetableRepository private constructor(private val app: Application) {

    private val database = TimetableDatabase.get(app)
    private val dao = database.timetableDao()

    /** Single flow used by the timetable view; updates whenever any part of the model changes. */
    val data: Flow<TimetableData> = combine(
        listOf<Flow<Any>>(
            dao.observeTerms(),
            dao.observeCourses(),
            dao.observeCourseTerms(),
            dao.observeRegularSlots(),
            dao.observeOneTimeSlots(),
            dao.observeFreeDays(),
            dao.observeTasks(),
            dao.observeChecklistItems(),
        )
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        TimetableData(
            terms = values[0] as List<Term>,
            courses = values[1] as List<Course>,
            courseTerms = values[2] as List<CourseTerm>,
            regularSlots = values[3] as List<RegularSlot>,
            oneTimeSlots = values[4] as List<OneTimeSlot>,
            freeDays = values[5] as List<FreeDay>,
            tasks = values[6] as List<Task>,
            checklistItems = values[7] as List<ChecklistItem>,
        )
    }

    // ---- CRUD -------------------------------------------------------------
    suspend fun addTerm(name: String, start: LocalDate, end: LocalDate) =
        dao.insertTerm(Term(name = name, startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay()))

    suspend fun updateTerm(term: Term) = dao.updateTerm(term)

    suspend fun generatedEventCount(termId: Long): Int = dao.generatedEvents(termId).size

    /**
     * Deletes a term with its slots and free days. Its generated class events are either
     * deleted or kept as ordinary events (marker removed, so later regeneration or a
     * backup restore never touches them).
     */
    suspend fun deleteTerm(term: Term, deleteEvents: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        val resolver = app.contentResolver
        for (generated in dao.generatedEvents(term.id)) {
            if (deleteEvents) {
                resolver.delete(eventUri(generated.eventId), null, null)
            } else {
                resolver.delete(extendedPropertiesUri(),
                    "${ExtendedProperties.EVENT_ID}=? AND ${ExtendedProperties.NAME}=?",
                    arrayOf(generated.eventId.toString(), MARKER))
            }
        }
        dao.deleteTerm(term)
    }

    suspend fun addCourse(name: String, teacher: String, room: String, color: Int) =
        dao.insertCourse(Course(name = name, teacher = teacher, room = room, color = color))

    suspend fun addCourse(course: Course) = dao.insertCourse(course.copy(id = 0))

    suspend fun updateCourse(course: Course) = dao.updateCourse(course)

    suspend fun deleteCourse(course: Course) = dao.deleteCourse(course)

    suspend fun setCourseInTerm(courseId: Long, termId: Long, linked: Boolean) {
        if (linked) dao.insertCourseTerm(CourseTerm(courseId, termId))
        else dao.unlinkCourse(courseId, termId)
    }

    suspend fun addRegularSlot(slot: RegularSlot) = dao.insertRegularSlot(slot)

    suspend fun updateRegularSlot(slot: RegularSlot) = dao.updateRegularSlot(slot)

    suspend fun deleteRegularSlot(slot: RegularSlot) = dao.deleteRegularSlot(slot)

    suspend fun addOneTimeSlot(slot: OneTimeSlot) = dao.insertOneTimeSlot(slot)

    suspend fun updateOneTimeSlot(slot: OneTimeSlot) = dao.updateOneTimeSlot(slot)

    suspend fun deleteOneTimeSlot(slot: OneTimeSlot) = dao.deleteOneTimeSlot(slot)

    suspend fun addFreeDay(termId: Long, date: LocalDate) =
        dao.insertFreeDay(FreeDay(termId = termId, dateEpochDay = date.toEpochDay()))

    suspend fun deleteFreeDay(freeDay: FreeDay) = dao.deleteFreeDay(freeDay)

    suspend fun addTask(task: Task) = dao.insertTask(task)

    suspend fun updateTask(task: Task) = dao.updateTask(task)

    suspend fun deleteTask(task: Task) = dao.deleteTask(task)

    // ---- Task sync (OpenTasks bridge) ---------------------------------------
    suspend fun allTasks(): List<Task> = dao.allTasks()

    suspend fun clearRemoteLinks() = dao.clearRemoteLinks()

    suspend fun linkRemote(taskId: Long, remoteId: Long, remoteModified: Long) =
        dao.linkRemote(taskId, remoteId, remoteModified)

    /** Stores a task coming from sync as-is (no modification stamp, no sync trigger). */
    suspend fun saveSyncedTask(task: Task): Long = withContext(Dispatchers.IO) {
        if (task.id == 0L) {
            val id = dao.insertTask(task)
            val saved = task.copy(id = id)
            dao.updateTask(saved.copy(calendarEventId = syncTaskEvent(saved)))
            id
        } else {
            dao.updateTask(task.copy(calendarEventId = syncTaskEvent(task)))
            task.id
        }.also { DueSummary.schedule(app) }
    }

    /** Deletes a task that was deleted remotely (does not touch the remote side). */
    suspend fun deleteLocalOnly(task: Task) = withContext(Dispatchers.IO) {
        task.calendarEventId?.let { app.contentResolver.delete(eventUri(it), null, null) }
        dao.deleteTask(task)
        DueSummary.schedule(app)
    }

    /** Replaces a task's checklist with [items] (in order). */
    suspend fun setChecklist(taskId: Long, items: List<ChecklistItem>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteChecklist(taskId)
            dao.insertChecklistItems(items.mapIndexed { index, item ->
                item.copy(id = 0, taskId = taskId, position = index)
            })
        }
    }

    suspend fun setChecklistItemDone(item: ChecklistItem, done: Boolean) =
        dao.updateChecklistItem(item.copy(done = done))

    /** Inserts a task and mirrors its due date into the managed calendar. */
    suspend fun addTaskSynced(task: Task): Long = withContext(Dispatchers.IO) {
        val id = dao.insertTask(task)
        val saved = task.copy(id = id)
        val eventId = syncTaskEvent(saved)
        if (eventId != saved.calendarEventId) dao.updateTask(saved.copy(calendarEventId = eventId))
        DueSummary.schedule(app)
        OpenTasksSync.requestSync(app)
        id
    }

    suspend fun updateTaskSynced(task: Task) = withContext(Dispatchers.IO) {
        val changed = task.copy(modifiedAtMillis = System.currentTimeMillis())
        dao.updateTask(changed.copy(calendarEventId = syncTaskEvent(changed)))
        DueSummary.schedule(app)
        OpenTasksSync.requestSync(app)
    }

    /**
     * Checks a task off (or back on). Completing a repeating task creates its next
     * occurrence (with a fresh copy of the checklist); the completed one stops repeating.
     */
    suspend fun setTaskCompleted(task: Task, completed: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rule = RepeatRule.parse(task.repeatRule)
        val due = task.dueAtMillis
        if (completed && rule != null && due != null) {
            val zone = ZoneId.systemDefault()
            rule.next(Instant.ofEpochMilli(due).atZone(zone).toLocalDateTime())?.let { next ->
                val nextMillis = next.atZone(zone).toInstant().toEpochMilli()
                val newId = addTaskSynced(task.copy(
                    id = 0, dueAtMillis = nextMillis, endAtMillis = task.endAtMillis?.plus(nextMillis - due),
                    completed = false, completedAtMillis = null, createdAtMillis = now, modifiedAtMillis = now,
                    calendarEventId = null, remoteId = null, remoteModifiedAtMillis = null,
                ))
                setChecklist(newId, dao.checklist(task.id).map { it.copy(done = false) })
            }
            updateTaskSynced(task.copy(completed = true, completedAtMillis = now, repeatRule = null))
        } else {
            updateTaskSynced(task.copy(completed = completed, completedAtMillis = if (completed) now else null))
        }
    }

    suspend fun deleteTaskSynced(task: Task) = withContext(Dispatchers.IO) {
        task.remoteId?.let { OpenTasksSync.deleteRemote(app, it) }
        task.calendarEventId?.let { app.contentResolver.delete(eventUri(it), null, null) }
        dao.deleteTask(task)
        DueSummary.schedule(app)
    }

    /**
     * Creates, updates or removes the calendar event mirroring [task]'s due date.
     * Completed tasks and tasks without a due date have no event. Returns the event id.
     */
    private suspend fun syncTaskEvent(task: Task): Long? {
        val resolver = app.contentResolver
        val existingId = task.calendarEventId?.takeIf { id ->
            resolver.query(eventUri(id), arrayOf(Events._ID, Events.DELETED), null, null, null)?.use {
                it.moveToFirst() && it.getInt(1) == 0
            } ?: false
        }
        val due = task.dueAtMillis
        // Exams are appointments and keep their event; tasks lose it once done.
        if ((task.completed && !task.isExam) || due == null) {
            existingId?.let { resolver.delete(eventUri(it), null, null) }
            return null
        }
        val color = task.courseId?.let { cid -> dao.courses().firstOrNull { it.id == cid }?.color }
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, ensureTimetableCalendar())
            val prefix = if (task.isExam) R.string.timetable_exam_event_prefix else R.string.timetable_task_event_prefix
            put(Events.TITLE, app.getString(prefix, task.title))
            put(Events.DESCRIPTION, task.note)
            put(Events.DTSTART, due)
            put(Events.DTEND, if (task.isExam) (task.endAtMillis?.takeIf { it > due } ?: (due + EXAM_DEFAULT_DURATION_MS))
                else due + TASK_EVENT_DURATION_MS)
            put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(Events.ALL_DAY, 0)
            put(Events.HAS_ALARM, 0)
            if (color != null) put(Events.EVENT_COLOR_KEY, eventColorKey(color)) else putNull(Events.EVENT_COLOR_KEY)
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        }
        val reminder = if (task.isExam) ReminderSettings.examMinutes(app) else ReminderSettings.taskMinutes(app)
        if (existingId != null) {
            resolver.update(eventUri(existingId), values, null, null)
            setEventReminder(existingId, reminder)
            return existingId
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: return null
        val eventId = ContentUris.parseId(uri)
        setEventReminder(eventId, reminder)
        resolver.insert(extendedPropertiesUri(), ContentValues().apply {
            put(ExtendedProperties.EVENT_ID, eventId)
            put(ExtendedProperties.NAME, TASK_MARKER)
            put(ExtendedProperties.VALUE, task.id.toString())
        })
        return eventId
    }

    // ---- Export / import --------------------------------------------------

    /** All tasks as an RFC 5545 calendar of VTODOs. */
    suspend fun exportTasksIcs(): String = withContext(Dispatchers.IO) {
        TaskIcs.write(dao.allTasks(), dao.courses())
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        TimetableBackup.encode(data.first())
    }

    /**
     * Imports VTODOs as plain tasks (not linked to a course, see US-5.2) and mirrors
     * their due dates into the calendar. Returns the number of tasks created.
     */
    suspend fun importTodos(todos: List<TaskIcs.Todo>): Int = withContext(Dispatchers.IO) {
        val untitled = app.getString(R.string.todo_untitled)
        for (todo in todos) {
            addTaskSynced(Task(
                title = todo.summary.ifEmpty { untitled },
                note = todo.description,
                dueAtMillis = todo.dueMillis,
                completed = todo.completed,
                completedAtMillis = if (todo.completed) todo.completedMillis ?: System.currentTimeMillis() else null,
                priority = TaskSyncEngine.fromRfcPriority(todo.priority),
            ))
        }
        todos.size
    }

    /**
     * Replaces all timetable data with [backup]. Calendar events this app created
     * (class events and task deadlines) are removed and rebuilt from the restored data.
     */
    suspend fun restoreBackup(backup: TimetableData) = withContext(Dispatchers.IO + NonCancellable) {
        val resolver = app.contentResolver
        // Tracked events plus any marked events whose bookkeeping was lost.
        val oldEventIds = dao.allGeneratedEvents().map { it.eventId } +
            dao.allTasks().mapNotNull { it.calendarEventId } + markedEventIds()
        oldEventIds.toSet().forEach { resolver.delete(eventUri(it), null, null) }

        // Task sync: restored tasks that are the same task as before (same id and creation
        // time) keep their remote link and are pushed again; remote copies of tasks that
        // the restore removes are deleted, so the server matches the restored state.
        val now = System.currentTimeMillis()
        val oldLinks = dao.allTasks().filter { it.remoteId != null }
            .associateBy { it.id to it.createdAtMillis }
        val restoredTasks = backup.tasks.map { task ->
            val old = oldLinks[task.id to task.createdAtMillis]
            task.copy(calendarEventId = null, remoteId = old?.remoteId,
                remoteModifiedAtMillis = old?.remoteModifiedAtMillis,
                modifiedAtMillis = if (old != null) now else task.modifiedAtMillis)
        }
        val keptRemote = restoredTasks.mapNotNull { it.remoteId }.toSet()
        oldLinks.values.mapNotNull { it.remoteId }.filter { it !in keptRemote }
            .forEach { OpenTasksSync.deleteRemote(app, it) }

        database.withTransaction {
            dao.deleteAllGeneratedEvents()
            dao.deleteAllTasks()
            dao.deleteAllFreeDays()
            dao.deleteAllOneTimeSlots()
            dao.deleteAllRegularSlots()
            dao.deleteAllCourseTerms()
            dao.deleteAllCourses()
            dao.deleteAllTerms()
            dao.insertTerms(backup.terms)
            dao.insertCourses(backup.courses)
            dao.insertCourseTerms(backup.courseTerms)
            dao.insertRegularSlots(backup.regularSlots)
            dao.insertOneTimeSlots(backup.oneTimeSlots)
            dao.insertFreeDays(backup.freeDays)
            dao.insertTasks(restoredTasks)
            dao.insertChecklistItems(backup.checklistItems)
        }

        for (term in dao.allTerms()) applyGeneration(buildPreview(term.id))
        // Rebuild deadline events; restored tasks that were never synced get pushed as new.
        for (task in dao.allTasks()) saveSyncedTask(task)
        OpenTasksSync.requestSync(app)
    }

    /** Used by the event editor to warn that an event belongs to the timetable. */
    suspend fun generatedEventFor(eventId: Long): GeneratedEvent? = dao.generatedEventByEventId(eventId)

    suspend fun coursesOnce(): List<Course> = dao.courses()

    // ---- Generation -------------------------------------------------------
    suspend fun buildPreview(termId: Long): GenerationPreview = withContext(Dispatchers.IO) {
        val term = dao.term(termId) ?: return@withContext GenerationPreview(termId)
        val courses = dao.courses().associateBy { it.id }
        val freeDays = dao.freeDays(termId).map { it.dateEpochDay }.toSet()
        val regular = dao.regularSlots(termId)
        val oneTime = dao.oneTimeSlots(termId)

        val desired = mutableMapOf<String, PlannedEvent>()
        var day = term.startDate
        while (!day.isAfter(term.endDate)) {
            val epochDay = day.toEpochDay()
            if (epochDay !in freeDays) {
                for (slot in regular) {
                    if (slot.weekday == day.dayOfWeek.value &&
                        courses[slot.courseId]?.isActiveOn(epochDay) != false
                    ) {
                        val event = plannedEvent(epochDay, slot.slotType(), slot.id, slot.courseId,
                            slot.termId, slot.startMinute, slot.endMinute, courses)
                        desired[event.key] = event
                    }
                }
            }
            day = day.plusDays(1)
        }
        for (slot in oneTime) {
            val epochDay = slot.dateEpochDay
            if (epochDay in term.startEpochDay..term.endEpochDay && epochDay !in freeDays) {
                val event = plannedEvent(epochDay, "once", slot.id, slot.courseId, slot.termId,
                    slot.startMinute, slot.endMinute, courses)
                desired[event.key] = event
            }
        }

        val toCreate = mutableListOf<PlannedEvent>()
        val toUpdate = mutableListOf<PlannedEvent>()
        val toDelete = mutableListOf<GeneratedEvent>()
        val stale = mutableListOf<GeneratedEvent>()
        val kept = mutableListOf<GeneratedEvent>()
        val details = mutableListOf<String>()

        for (generated in dao.generatedEvents(termId)) {
            val current = readEventFingerprint(generated.eventId)
            val wanted = desired.remove(generated.eventKey)
            when {
                current == null -> {
                    // The provider event is gone; drop the stale bookkeeping row.
                    stale += generated
                    if (wanted != null) {
                        toCreate += wanted
                        details += "Recreate: ${label(wanted)}"
                    }
                }
                wanted == null -> {
                    if (current == generated.fingerprint) {
                        toDelete += generated
                        details += "Delete: ${generated.eventKey}"
                    } else {
                        kept += generated // user edited an obsolete event: keep it
                    }
                }
                current != generated.fingerprint -> {
                    // The user changed the event in a calendar app: keep it as-is.
                    kept += generated
                }
                current != wanted.fingerprint || generated.color != wanted.color -> {
                    toUpdate += wanted
                    details += "Update: ${label(wanted)}"
                }
            }
        }
        for (wanted in desired.values) {
            toCreate += wanted
            details += "Add: ${label(wanted)}"
        }

        GenerationPreview(
            termId = termId, toCreate = toCreate, toUpdate = toUpdate,
            toDelete = toDelete, stale = stale, userEditedKept = kept,
            details = details.take(10),
        )
    }

    /** Applies a reviewed preview to the CalendarProvider and updates our bookkeeping. */
    suspend fun applyGeneration(preview: GenerationPreview) = withContext(Dispatchers.IO) {
        val calendarId = ensureTimetableCalendar()
        val existingByKey = dao.generatedEvents(preview.termId).associateBy { it.eventKey }
        val resolver = app.contentResolver
        val operations = ArrayList<ContentProviderOperation>()
        val extUri = extendedPropertiesUri()

        val eventIdByKey = HashMap<String, Long>()
        preview.toDelete.forEach { generated ->
            resolver.delete(eventUri(generated.eventId), null, null)
            dao.deleteGeneratedEventByKey(generated.eventKey)
        }
        preview.stale.forEach { dao.deleteGeneratedEventByKey(it.eventKey) }

        for (planned in preview.toUpdate) {
            val existing = existingByKey[planned.key] ?: continue
            val id = existing.eventId
            eventIdByKey[planned.key] = id
            operations += ContentProviderOperation.newUpdate(eventUri(id))
                .withValues(eventValues(planned, calendarId)).build()
            operations += ContentProviderOperation.newDelete(extUri)
                .withSelection("${ExtendedProperties.EVENT_ID}=? AND ${ExtendedProperties.NAME}=?",
                    arrayOf(id.toString(), MARKER)).build()
            operations += ContentProviderOperation.newInsert(extUri)
                .withValue(ExtendedProperties.EVENT_ID, id)
                .withValue(ExtendedProperties.NAME, MARKER)
                .withValue(ExtendedProperties.VALUE, propertyValue(planned))
                .build()
        }

        val insertIndexes = HashMap<String, Int>()
        for (planned in preview.toCreate) {
            insertIndexes[planned.key] = operations.size
            operations += ContentProviderOperation.newInsert(Events.CONTENT_URI)
                .withValues(eventValues(planned, calendarId)).build()
            operations += ContentProviderOperation.newInsert(extUri)
                .withValueBackReference(ExtendedProperties.EVENT_ID, insertIndexes[planned.key]!!)
                .withValue(ExtendedProperties.NAME, MARKER)
                .withValue(ExtendedProperties.VALUE, propertyValue(planned))
                .build()
        }

        val results = if (operations.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, operations) else emptyArray()

        for (planned in preview.toUpdate) {
            val id = eventIdByKey[planned.key] ?: continue
            dao.insertGeneratedEvent(GeneratedEvent(
                termId = preview.termId, eventKey = planned.key, eventId = id,
                calendarId = calendarId, fingerprint = planned.fingerprint, color = planned.color,
            ))
        }
        for ((key, index) in insertIndexes) {
            val planned = preview.toCreate.first { it.key == key }
            val resultUri = results[index].uri ?: continue
            val id = android.content.ContentUris.parseId(resultUri)
            dao.insertGeneratedEvent(GeneratedEvent(
                termId = preview.termId, eventKey = key, eventId = id,
                calendarId = calendarId, fingerprint = planned.fingerprint, color = planned.color,
            ))
        }
        for (generated in preview.userEditedKept) {
            dao.insertGeneratedEvent(generated.copy(userEdited = true))
        }
        applyClassReminders(dao.generatedEvents(preview.termId))
        DueSummary.schedule(app)
        Unit
    }

    /**
     * Re-applies the reminder settings to all class, task and exam events
     * (called when the settings change). Events the user edited are left alone.
     */
    suspend fun applyReminderSettings() = withContext(Dispatchers.IO) {
        applyClassReminders(dao.allGeneratedEvents())
        for (task in dao.allTasks()) {
            val eventId = task.calendarEventId ?: continue
            setEventReminder(eventId,
                if (task.isExam) ReminderSettings.examMinutes(app) else ReminderSettings.taskMinutes(app))
        }
        DueSummary.schedule(app)
    }

    private fun applyClassReminders(events: List<GeneratedEvent>) {
        val minutes = ReminderSettings.classMinutes(app)
        events.filter { !it.userEdited }.forEach { setEventReminder(it.eventId, minutes) }
    }

    /** Replaces an event's reminders with one alert [minutes] before (none for [ReminderSettings.OFF]). */
    private fun setEventReminder(eventId: Long, minutes: Int) {
        val resolver = app.contentResolver
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
        if (minutes != ReminderSettings.OFF) {
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        resolver.update(eventUri(eventId), ContentValues().apply {
            put(Events.HAS_ALARM, if (minutes != ReminderSettings.OFF) 1 else 0)
        }, null, null)
    }

    // ---- Helpers ----------------------------------------------------------
    private fun RegularSlot.slotType() = "regular"

    private fun plannedEvent(
        epochDay: Long, slotType: String, slotId: Long, courseId: Long, termId: Long,
        startMinute: Int, endMinute: Int, courses: Map<Long, Course>,
    ): PlannedEvent {
        val course = courses[courseId]
        val title = course?.name ?: app.getString(R.string.timetable_unnamed_course)
        val location = course?.room.orEmpty()
        val description = buildString {
            course?.teacher?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            append(app.getString(R.string.timetable_generated_description))
        }
        return PlannedEvent(
            key = "$slotType:$slotId:$epochDay", slotType = slotType, slotId = slotId,
            termId = termId, epochDay = epochDay, title = title, location = location,
            description = description, color = course?.color ?: 0xFF1454B9.toInt(),
            startMillis = epochDayToMillis(epochDay, startMinute),
            endMillis = epochDayToMillis(epochDay, endMinute),
        )
    }

    private fun label(event: PlannedEvent): String {
        val date = LocalDate.ofEpochDay(event.epochDay)
        return "${event.title} · ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} " +
            "${date.monthValue}/${date.dayOfMonth} ${minutesToTime(((event.startMillis - epochDayToMillis(event.epochDay, 0)) / 60000).toInt())}"
    }

    private fun readEventFingerprint(eventId: Long): String? {
        val projection = arrayOf(
            Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION,
            Events.DTSTART, Events.DTEND,
        )
        app.contentResolver.query(eventUri(eventId), projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return listOf(
                cursor.getString(cursor.getColumnIndex(Events.TITLE)).orEmpty(),
                cursor.getString(cursor.getColumnIndex(Events.EVENT_LOCATION)).orEmpty(),
                cursor.getString(cursor.getColumnIndex(Events.DESCRIPTION)).orEmpty(),
                cursor.getLong(cursor.getColumnIndex(Events.DTSTART)),
                cursor.getLong(cursor.getColumnIndex(Events.DTEND)),
            ).joinToString("\u001f")
        }
        return null
    }

    private fun eventUri(eventId: Long): Uri = android.content.ContentUris.withAppendedId(Events.CONTENT_URI, eventId)

    private fun eventValues(event: PlannedEvent, calendarId: Long) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, event.title)
        put(Events.EVENT_LOCATION, event.location)
        put(Events.DESCRIPTION, event.description)
        put(Events.DTSTART, event.startMillis)
        put(Events.DTEND, event.endMillis)
        put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        put(Events.EVENT_COLOR_KEY, eventColorKey(event.color))
        put(Events.ALL_DAY, 0)
        put(Events.HAS_ALARM, 0)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
    }

    /** Course colours come from Etar's palette; map them to a colour key in the Colors table. */
    private fun eventColorKey(color: Int): String {
        val palette = app.resources.getIntArray(R.array.defaultCalendarColors)
        val index = palette.indexOf(color)
        return if (index >= 0) index.toString() else "0"
    }

    /** The CalendarProvider requires Colors rows for an account before using a color key. */
    private fun ensureCalendarColors() {
        val resolver = app.contentResolver
        resolver.query(
            Colors.CONTENT_URI, null,
            "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=?",
            arrayOf(CALENDAR_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null
        )?.use { if (it.moveToFirst()) return }

        val values = ArrayList<ContentValues>()
        app.resources.getIntArray(R.array.defaultCalendarColors).forEachIndexed { index, color ->
            for (type in arrayOf(Colors.TYPE_CALENDAR, Colors.TYPE_EVENT)) {
                values += ContentValues().apply {
                    put(Colors.ACCOUNT_NAME, CALENDAR_ACCOUNT)
                    put(Colors.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(Colors.COLOR_TYPE, type)
                    put(Colors.COLOR_KEY, index.toString())
                    put(Colors.COLOR, color)
                }
            }
        }
        resolver.bulkInsert(
            CalendarRepository.asLocalCalendarSyncAdapter(CALENDAR_ACCOUNT, Colors.CONTENT_URI),
            values.toTypedArray()
        )
    }

    private fun propertyValue(event: PlannedEvent) = JSONObject().apply {
        put("key", event.key)
        put("termId", event.termId)
        put("slotType", event.slotType)
        put("slotId", event.slotId)
        put("fingerprint", event.fingerprint)
    }.toString()

    /** Ids of all events carrying one of this app's ExtendedProperties markers. */
    private fun markedEventIds(): List<Long> {
        val ids = ArrayList<Long>()
        app.contentResolver.query(
            extendedPropertiesUri(), arrayOf(ExtendedProperties.EVENT_ID),
            "${ExtendedProperties.NAME} IN (?, ?)", arrayOf(MARKER, TASK_MARKER), null
        )?.use { while (it.moveToNext()) ids += it.getLong(0) }
        return ids
    }

    private fun extendedPropertiesUri(): Uri =
        CalendarRepository.asLocalCalendarSyncAdapter(CALENDAR_ACCOUNT, ExtendedProperties.CONTENT_URI)

    private fun ensureTimetableCalendar(): Long {
        ensureCalendarColors()
        val resolver = app.contentResolver
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_CALENDAR_ID, -1L)
        if (saved != -1L) {
            resolver.query(android.content.ContentUris.withAppendedId(Calendars.CONTENT_URI, saved),
                arrayOf(Calendars._ID), null, null, null)?.use { if (it.moveToFirst()) return saved }
        }
        resolver.query(
            CalendarRepository.asLocalCalendarSyncAdapter(CALENDAR_ACCOUNT, Calendars.CONTENT_URI),
            arrayOf(Calendars._ID), "${Calendars.NAME}=?", arrayOf(CALENDAR_NAME), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndex(Calendars._ID))
                prefs.edit().putLong(KEY_CALENDAR_ID, id).apply()
                return id
            }
        }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, CALENDAR_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.OWNER_ACCOUNT, CALENDAR_ACCOUNT)
            put(Calendars.NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, app.getString(R.string.timetable_calendar_name))
            put(Calendars.CALENDAR_COLOR_KEY, "1")
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_ROOT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.IS_PRIMARY, 0)
            put(Calendars.CAN_ORGANIZER_RESPOND, 0)
            put(Calendars.CAN_MODIFY_TIME_ZONE, 1)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.ALLOWED_ATTENDEE_TYPES, Attendees.TYPE_NONE.toString())
        }
        val uri = resolver.insert(
            CalendarRepository.asLocalCalendarSyncAdapter(CALENDAR_ACCOUNT, Calendars.CONTENT_URI),
            values
        ) ?: throw IllegalStateException("Could not create timetable calendar")
        val id = android.content.ContentUris.parseId(uri)
        prefs.edit().putLong(KEY_CALENDAR_ID, id).apply()
        return id
    }

    companion object {
        private const val MARKER = "com.android.calendar.timetable.generated"
        private const val TASK_MARKER = "com.android.calendar.timetable.task"
        private const val TASK_EVENT_DURATION_MS = 15 * 60 * 1000L
        private const val EXAM_DEFAULT_DURATION_MS = 90 * 60 * 1000L
        private const val CALENDAR_ACCOUNT = "etar_timetable"
        private const val CALENDAR_NAME = "etar_timetable"
        private const val PREFS_NAME = "etar_timetable_prefs"
        private const val KEY_CALENDAR_ID = "calendar_id"

        @Volatile
        private var instance: TimetableRepository? = null

        fun get(context: Context): TimetableRepository =
            instance ?: synchronized(this) {
                instance ?: TimetableRepository(context.applicationContext as Application).also { instance = it }
            }
    }
}
