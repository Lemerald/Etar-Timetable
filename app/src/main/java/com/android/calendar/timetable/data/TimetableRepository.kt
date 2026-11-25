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
import com.android.calendar.persistence.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private val dao = TimetableDatabase.get(app).timetableDao()

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
        )
    }

    // ---- CRUD -------------------------------------------------------------
    suspend fun addTerm(name: String, start: LocalDate, end: LocalDate) =
        dao.insertTerm(Term(name = name, startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay()))

    suspend fun updateTerm(term: Term) = dao.updateTerm(term)

    suspend fun deleteTerm(term: Term) = dao.deleteTerm(term)

    suspend fun addCourse(name: String, teacher: String, room: String, color: Int) =
        dao.insertCourse(Course(name = name, teacher = teacher, room = room, color = color))

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

    /** Inserts a task and mirrors its due date into the managed calendar. */
    suspend fun addTaskSynced(task: Task): Long = withContext(Dispatchers.IO) {
        val id = dao.insertTask(task)
        val saved = task.copy(id = id)
        val eventId = syncTaskEvent(saved)
        if (eventId != saved.calendarEventId) dao.updateTask(saved.copy(calendarEventId = eventId))
        id
    }

    suspend fun updateTaskSynced(task: Task) = withContext(Dispatchers.IO) {
        dao.updateTask(task.copy(calendarEventId = syncTaskEvent(task)))
    }

    suspend fun deleteTaskSynced(task: Task) = withContext(Dispatchers.IO) {
        task.calendarEventId?.let { app.contentResolver.delete(eventUri(it), null, null) }
        dao.deleteTask(task)
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
        if (task.completed || due == null) {
            existingId?.let { resolver.delete(eventUri(it), null, null) }
            return null
        }
        val color = task.courseId?.let { cid -> dao.courses().firstOrNull { it.id == cid }?.color }
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, ensureTimetableCalendar())
            put(Events.TITLE, app.getString(R.string.timetable_task_event_prefix, task.title))
            put(Events.DESCRIPTION, task.note)
            put(Events.DTSTART, due)
            put(Events.DTEND, due + TASK_EVENT_DURATION_MS)
            put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(Events.ALL_DAY, 0)
            put(Events.HAS_ALARM, 0)
            if (color != null) put(Events.EVENT_COLOR_KEY, eventColorKey(color)) else putNull(Events.EVENT_COLOR_KEY)
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        }
        if (existingId != null) {
            resolver.update(eventUri(existingId), values, null, null)
            return existingId
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: return null
        val eventId = ContentUris.parseId(uri)
        resolver.insert(extendedPropertiesUri(), ContentValues().apply {
            put(ExtendedProperties.EVENT_ID, eventId)
            put(ExtendedProperties.NAME, TASK_MARKER)
            put(ExtendedProperties.VALUE, task.id.toString())
        })
        return eventId
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
                    if (slot.weekday == day.dayOfWeek.value) {
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
        Unit
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
