package com.android.calendar.timetable.ui.manage

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.calendar.timetable.data.Building
import com.android.calendar.timetable.data.Campus
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.CourseTerm
import com.android.calendar.timetable.data.FreeDay
import com.android.calendar.timetable.data.GenerationPreview
import com.android.calendar.timetable.data.OneTimeSlot
import com.android.calendar.timetable.data.RegularSlot
import com.android.calendar.timetable.data.SlotPlace
import com.android.calendar.timetable.data.Term
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.TimetableRepository
import com.android.calendar.timetable.data.minutesToTime
import com.android.calendar.timetable.io.TimetableBackup
import com.android.calendar.timetable.reminders.ReminderSettings
import com.android.calendar.timetable.sync.OpenTasksSync
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import ws.xsoh.etar.R

/** Editor for terms, course-term links, slots and free days, with generation preview. */
class TimetableEditorFragment : Fragment(R.layout.fragment_timetable_editor) {

    private val repository by lazy { TimetableRepository.get(requireContext()) }
    private lateinit var container: LinearLayout
    private var data = TimetableData()
    private var activeTermId = -1L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.editor_container)
        arguments?.getLong(TimetableEditorActivity.EXTRA_TERM_ID, -1L)
            ?.takeIf { it != -1L }?.let { activeTermId = it }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.data.collect { newData ->
                    data = newData
                    if (data.terms.isNotEmpty() && activeTermId !in data.terms.map { it.id }) {
                        activeTermId = data.terms.first().id
                    }
                    render()
                }
            }
        }
    }

    private fun render() {
        container.removeAllViews()
        addButton(getString(R.string.timetable_new_term)) { showTermDialog(null) }
        if (data.terms.isEmpty()) {
            addNote(getString(R.string.timetable_no_terms))
            renderPlacesSection()
        renderReminderSection()
        renderSyncSection()
        renderBackupSection()
            return
        }
        val term = data.terms.first { it.id == activeTermId }

        addHeading(getString(R.string.timetable_term))
        val termRow = row()
        val spinner = Spinner(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
                data.terms.map { it.name }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(data.terms.indexOf(term).coerceAtLeast(0))
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position in data.terms.indices && data.terms[position].id != activeTermId) {
                    activeTermId = data.terms[position].id
                    render()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        termRow.addView(spinner)
        termRow.addView(iconButton(R.drawable.outline_edit_event, R.string.timetable_edit_term) {
            showTermDialog(term)
        })
        termRow.addView(iconButton(R.drawable.outline_delete_event, R.string.timetable_delete_term) {
            launch {
                val eventCount = repository.generatedEventCount(term.id)
                if (!isAdded) return@launch
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.timetable_delete_term)
                    .setNegativeButton(android.R.string.cancel, null)
                if (eventCount == 0) {
                    builder.setMessage(getString(R.string.timetable_delete_term_confirm, term.name))
                        .setPositiveButton(R.string.timetable_delete_term) { _, _ ->
                            launch { repository.deleteTerm(term, deleteEvents = false) }
                        }
                } else {
                    builder.setMessage(resources.getQuantityString(R.plurals.timetable_delete_term_with_events,
                        eventCount, term.name, eventCount))
                        .setPositiveButton(R.string.timetable_delete_term_keep_events) { _, _ ->
                            launch { repository.deleteTerm(term, deleteEvents = false) }
                        }
                        .setNeutralButton(R.string.timetable_delete_term_and_events) { _, _ ->
                            launch { repository.deleteTerm(term, deleteEvents = true) }
                        }
                }
                builder.show()
            }
        })
        container.addView(termRow)
        addNote("${term.startDate.format(DATE_FORMAT)} – ${term.endDate.format(DATE_FORMAT)}" +
            (term.campusId?.let { id -> data.campuses.firstOrNull { it.id == id } }?.let { " · ${it.name}" } ?: ""))

        val linkedIds = data.courseTerms.filter { it.termId == term.id }.map { it.courseId }.toSet()

        // Courses (checkbox links the course to this term).
        addHeading(getString(R.string.timetable_courses))
        addButton(getString(R.string.timetable_new_course)) { showCourseDialog(null) }
        if (data.courses.isEmpty()) addNote(getString(R.string.timetable_no_courses_in_term))
        for (course in data.courses) {
            val courseRow = row()
            val check = CheckBox(requireContext()).apply {
                text = course.name
                isChecked = course.id in linkedIds
                setOnCheckedChangeListener { _, checked ->
                    launch { repository.setCourseInTerm(course.id, term.id, checked) }
                }
            }
            courseRow.addView(check, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val swatch = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(course.color)
                }
            }
            courseRow.addView(swatch)
            courseRow.addView(iconButton(R.drawable.outline_edit_event, R.string.timetable_edit_course) {
                showCourseDialog(course)
            })
            courseRow.addView(iconButton(R.drawable.outline_delete_event, R.string.timetable_delete_slot) {
                confirm(getString(R.string.timetable_delete_slot), course.name) {
                    launch { repository.deleteCourse(course) }
                }
            })
            container.addView(courseRow)
        }

        // Regular weekly slots.
        addHeading(getString(R.string.timetable_regular_slots))
        addButton(getString(R.string.timetable_add_regular_slot)) { showRegularSlotDialog(null, linkedIds) }
        val regular = data.regularSlots.filter { it.termId == term.id }
        if (regular.isEmpty()) addNote(getString(R.string.timetable_no_courses_in_term))
        for (slot in regular) {
            val label = "${weekdayName(slot.weekday)} ${minutesToTime(slot.startMinute)}–" +
                "${minutesToTime(slot.endMinute)} · ${data.courseById(slot.courseId)?.name.orEmpty()}" +
                placeSuffix(data.placeOf(slot.termId, slot.courseId, slot.buildingId, slot.room))
            addEditableRow(label, onEdit = { showRegularSlotDialog(slot, linkedIds) }) {
                launch { repository.deleteRegularSlot(slot) }
            }
        }

        // One-off slots.
        addHeading(getString(R.string.timetable_one_time_slots))
        addButton(getString(R.string.timetable_add_one_time_slot)) { showOneTimeSlotDialog(null, linkedIds) }
        for (slot in data.oneTimeSlots.filter { it.termId == term.id }) {
            val label = "${slot.date.format(DATE_FORMAT)} ${minutesToTime(slot.startMinute)}–" +
                "${minutesToTime(slot.endMinute)} · ${data.courseById(slot.courseId)?.name.orEmpty()}" +
                placeSuffix(data.placeOf(slot.termId, slot.courseId, slot.buildingId, slot.room))
            addEditableRow(label, onEdit = { showOneTimeSlotDialog(slot, linkedIds) }) {
                launch { repository.deleteOneTimeSlot(slot) }
            }
        }

        // Free days.
        addHeading(getString(R.string.timetable_free_days))
        addButton(getString(R.string.timetable_add_free_day)) { showFreeDayDialog(term.id) }
        for (freeDay in data.freeDays.filter { it.termId == term.id }) {
            addEditableRow(freeDay.date.format(DATE_FORMAT), note = null,
                onDelete = { launch { repository.deleteFreeDay(freeDay) } })
        }

        addHeading(getString(R.string.timetable_generate))
        addButton(getString(R.string.timetable_generate)) {
            launch {
                val preview = repository.buildPreview(term.id)
                if (isAdded) showPreview(preview)
            }
        }
        renderPlacesSection()
        renderReminderSection()
        renderSyncSection()
        renderBackupSection()
    }

    // ---- Export / backup --------------------------------------------------
    private val exportTasksLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
            if (uri != null) writeDocument(uri) { repository.exportTasksIcs() }
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeDocument(uri) { repository.exportBackupJson() }
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBackup(uri)
        }

    // ---- Task sync with DAVx5 (via Tasks.org / OpenTasks) ---------------------
    private var pendingSyncProvider: OpenTasksSync.Provider? = null

    private val syncPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val provider = pendingSyncProvider ?: return@registerForActivityResult
            if (granted.values.all { it }) chooseTaskList(provider)
            else toast(R.string.timetable_sync_permission_denied)
        }

    private fun renderSyncSection() {
        val ctx = requireContext()
        addHeading(getString(R.string.timetable_sync_heading))
        val installed = OpenTasksSync.installedProviders(ctx)
        if (installed.isEmpty()) {
            addNote(getString(R.string.timetable_sync_no_provider))
            return
        }
        if (OpenTasksSync.isEnabled(ctx)) {
            val last = OpenTasksSync.lastSync(ctx).takeIf { it > 0 }?.let {
                Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d/M HH:mm", Locale.getDefault()))
            } ?: "–"
            addNote(getString(R.string.timetable_sync_status, OpenTasksSync.listName(ctx),
                OpenTasksSync.provider(ctx)?.appName, last, OpenTasksSync.lastResult(ctx) ?: "–"))
            addButton(getString(R.string.timetable_sync_now)) {
                launch {
                    OpenTasksSync.sync(ctx)
                    if (isAdded) render()
                }
            }
            addButton(getString(R.string.timetable_sync_change_list)) { startChoosingList(installed) }
            addButton(getString(R.string.timetable_sync_stop)) {
                confirm(getString(R.string.timetable_sync_stop), getString(R.string.timetable_sync_stop_confirm)) {
                    launch { OpenTasksSync.disable(ctx); if (isAdded) render() }
                }
            }
        } else {
            addNote(getString(R.string.timetable_sync_explanation))
            addButton(getString(R.string.timetable_sync_choose_list)) { startChoosingList(installed) }
        }
    }

    private fun startChoosingList(installed: List<OpenTasksSync.Provider>) {
        if (installed.size == 1) return requestSyncPermissions(installed.single())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.timetable_sync_choose_app)
            .setItems(installed.map { it.appName }.toTypedArray()) { _, which -> requestSyncPermissions(installed[which]) }
            .show()
    }

    private fun requestSyncPermissions(provider: OpenTasksSync.Provider) {
        pendingSyncProvider = provider
        if (OpenTasksSync.hasPermissions(requireContext(), provider)) chooseTaskList(provider)
        else syncPermissionLauncher.launch(provider.permissions)
    }

    private fun chooseTaskList(provider: OpenTasksSync.Provider) {
        val ctx = requireContext()
        launch {
            val lists = withContext(Dispatchers.IO) { OpenTasksSync.lists(ctx, provider) }
            if (!isAdded) return@launch
            if (lists.isEmpty()) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.timetable_sync_heading)
                    .setMessage(getString(R.string.timetable_sync_no_lists, provider.appName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.timetable_sync_choose_list)
                .setItems(lists.map { "${it.name} (${it.account})" }.toTypedArray()) { _, which ->
                    launch {
                        OpenTasksSync.choose(ctx, provider, lists[which])
                        if (isAdded) render()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- Universities, buildings and class locations ---------------------------

    private fun placeSuffix(place: SlotPlace): String = place.shortLabel?.let { " · $it" } ?: ""

    private fun renderPlacesSection() {
        addHeading(getString(R.string.timetable_places_heading))
        addNote(getString(R.string.timetable_places_note))
        for (campus in data.campuses) {
            addEditableRow(campus.name + "\n" + campus.address.ifBlank { getString(R.string.timetable_no_address) },
                onEdit = { showCampusDialog(campus) }) {
                confirm(getString(R.string.timetable_delete_university), campus.name) {
                    launch { repository.deleteCampus(campus); toast(R.string.timetable_locations_regenerate) }
                }
            }
            for (building in data.buildings.filter { it.campusId == campus.id }) {
                addEditableRow("    " + building.name + " · " +
                    building.address.ifBlank { getString(R.string.timetable_uses_university_address) },
                    onEdit = { showBuildingDialog(building, campus.id) }) {
                    confirm(getString(R.string.timetable_delete_building), building.name) {
                        launch { repository.deleteBuilding(building); toast(R.string.timetable_locations_regenerate) }
                    }
                }
            }
            addButton(getString(R.string.timetable_add_building_to, campus.name)) { showBuildingDialog(null, campus.id) }
        }
        addButton(getString(R.string.timetable_add_university)) { showCampusDialog(null) }
    }

    /** Address field plus a button that shows it in a maps app, to check it is found. */
    private fun addressField(layout: LinearLayout, initial: String, hint: Int): EditText {
        val ctx = requireContext()
        val address = EditText(ctx).apply {
            this.hint = getString(hint)
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(address)
        layout.addView(Button(ctx).apply {
            text = getString(R.string.timetable_check_on_map)
            setOnClickListener {
                val query = address.text.toString().trim()
                if (query.isEmpty()) return@setOnClickListener
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.timetable_no_maps_app)
                }
            }
        })
        return address
    }

    private fun showCampusDialog(existing: Campus?) {
        val ctx = requireContext()
        val layout = formLayout()
        val name = EditText(ctx).apply {
            hint = getString(R.string.timetable_university_name); setText(existing?.name.orEmpty())
        }
        layout.addView(name)
        val address = addressField(layout, existing?.address.orEmpty(), R.string.timetable_university_address)
        showForm(if (existing == null) R.string.timetable_add_university else R.string.timetable_edit_university, layout) {
            if (name.text.isBlank()) { toast(R.string.timetable_name_required); false }
            else {
                val campus = (existing ?: Campus(name = "")).copy(
                    name = name.text.toString().trim(), address = address.text.toString().trim())
                launch {
                    repository.saveCampus(campus)
                    if (existing != null && existing.address != campus.address) toast(R.string.timetable_locations_regenerate)
                }
                true
            }
        }
    }

    private fun showBuildingDialog(existing: Building?, campusId: Long) {
        val ctx = requireContext()
        val layout = formLayout()
        val name = EditText(ctx).apply {
            hint = getString(R.string.timetable_building_name); setText(existing?.name.orEmpty())
        }
        layout.addView(name)
        val address = addressField(layout, existing?.address.orEmpty(), R.string.timetable_building_address)
        showForm(if (existing == null) R.string.timetable_add_building else R.string.timetable_edit_building, layout) {
            if (name.text.isBlank()) { toast(R.string.timetable_name_required); false }
            else {
                val building = (existing ?: Building(campusId = campusId, name = "")).copy(
                    name = name.text.toString().trim(), address = address.text.toString().trim())
                launch {
                    repository.saveBuilding(building)
                    if (existing != null && existing != building) toast(R.string.timetable_locations_regenerate)
                }
                true
            }
        }
    }

    private class PlaceFields(val buildingId: () -> Long?, val room: () -> String?)

    /**
     * Building + room inputs for a slot, with a live preview of the resulting calendar
     * location. Buildings of the term's university are listed first.
     */
    private fun placeFields(
        layout: LinearLayout, courseSpinner: Spinner, courses: List<Course>,
        initialBuildingId: Long?, initialRoom: String?,
    ): PlaceFields {
        val ctx = requireContext()
        val term = data.terms.firstOrNull { it.id == activeTermId }
        val campusNames = data.campuses.associate { it.id to it.name }
        val buildings = data.buildings.sortedWith(compareBy({ it.campusId != term?.campusId },
            { campusNames[it.campusId] }, { it.name }))
        val buildingSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                listOf(getString(R.string.timetable_no_building)) + buildings.map {
                    if (it.campusId == term?.campusId) it.name else "${campusNames[it.campusId]} · ${it.name}"
                }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(buildings.indexOfFirst { it.id == initialBuildingId } + 1)
        }
        val room = EditText(ctx).apply { setText(initialRoom.orEmpty()) }
        val preview = TextView(ctx).apply { textSize = 12f }
        fun buildingId() = buildings.getOrNull(buildingSpinner.selectedItemPosition - 1)?.id
        fun roomText() = room.text.toString().trim().ifEmpty { null }
        fun refresh() {
            val course = courses.getOrNull(courseSpinner.selectedItemPosition)
            room.hint = course?.room?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.timetable_room_default, it) } ?: getString(R.string.timetable_room)
            val location = course?.let { data.placeOf(activeTermId, it.id, buildingId(), roomText()).eventLocation }
            preview.text = getString(R.string.timetable_location_preview,
                location?.ifEmpty { null } ?: getString(R.string.timetable_no_location))
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        buildingSpinner.onItemSelectedListener = listener
        courseSpinner.onItemSelectedListener = listener
        room.doAfterTextChanged { refresh() }
        if (buildings.isNotEmpty()) {
            layout.addView(TextView(ctx).apply { text = getString(R.string.timetable_building) })
            layout.addView(buildingSpinner)
        }
        layout.addView(room)
        layout.addView(preview)
        refresh()
        return PlaceFields(::buildingId, ::roomText)
    }

    private fun renderReminderSection() {
        val ctx = requireContext()
        addHeading(getString(R.string.timetable_reminders_heading))
        val classSpinner = reminderSpinner(R.string.timetable_reminder_class,
            ReminderSettings.CLASS_CHOICES, ReminderSettings.classMinutes(ctx))
        val taskSpinner = reminderSpinner(R.string.timetable_reminder_task,
            ReminderSettings.TASK_CHOICES, ReminderSettings.taskMinutes(ctx))
        val examSpinner = reminderSpinner(R.string.timetable_reminder_exam,
            ReminderSettings.EXAM_CHOICES, ReminderSettings.examMinutes(ctx))
        val summary = CheckBox(ctx).apply {
            text = getString(R.string.timetable_reminder_summary)
            isChecked = ReminderSettings.dueSummary(ctx)
        }
        container.addView(summary)
        fun save() {
            val values = listOf(
                ReminderSettings.CLASS_CHOICES[classSpinner.selectedItemPosition],
                ReminderSettings.TASK_CHOICES[taskSpinner.selectedItemPosition],
                ReminderSettings.EXAM_CHOICES[examSpinner.selectedItemPosition],
            )
            val unchanged = values == listOf(ReminderSettings.classMinutes(ctx), ReminderSettings.taskMinutes(ctx),
                ReminderSettings.examMinutes(ctx)) && summary.isChecked == ReminderSettings.dueSummary(ctx)
            if (unchanged) return
            ReminderSettings.set(ctx, values[0], values[1], values[2], summary.isChecked)
            launch { repository.applyReminderSettings() }
        }
        listOf(classSpinner, taskSpinner, examSpinner).forEach {
            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) = save()
                override fun onNothingSelected(parent: AdapterView<*>) = Unit
            }
        }
        summary.setOnCheckedChangeListener { _, _ -> save() }
    }

    /** A labelled spinner of reminder offsets; [current] is preselected. */
    private fun reminderSpinner(label: Int, choices: List<Int>, current: Int): Spinner {
        addNote(getString(label))
        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
                choices.map(::reminderLabel)).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(choices.indexOf(current).coerceAtLeast(0), false)
        }
        container.addView(spinner)
        return spinner
    }

    private fun reminderLabel(minutes: Int): String = when {
        minutes == ReminderSettings.OFF -> getString(R.string.timetable_reminder_off)
        minutes == 60 -> getString(R.string.timetable_reminder_one_hour)
        minutes == 1440 -> getString(R.string.timetable_reminder_one_day)
        minutes == 10080 -> getString(R.string.timetable_reminder_one_week)
        minutes % 1440 == 0 -> getString(R.string.timetable_reminder_days, minutes / 1440)
        minutes % 60 == 0 -> getString(R.string.timetable_reminder_hours, minutes / 60)
        else -> getString(R.string.timetable_reminder_minutes, minutes)
    }

    private fun renderBackupSection() {
        addHeading(getString(R.string.timetable_backup_heading))
        addNote(getString(R.string.timetable_backup_note))
        addButton(getString(R.string.timetable_export_tasks_ics)) {
            exportTasksLauncher.launch("tasks-${LocalDate.now()}.ics")
        }
        addButton(getString(R.string.timetable_export_backup)) {
            exportBackupLauncher.launch("timetable-backup-${LocalDate.now()}.json")
        }
        addButton(getString(R.string.timetable_restore_backup)) {
            // Some file managers label JSON as octet-stream or plain text.
            restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
    }

    private fun writeDocument(uri: Uri, content: suspend () -> String) {
        val context = requireContext().applicationContext
        launch {
            val ok = try {
                val text = content()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                } != null
            } catch (e: IOException) {
                false
            }
            Toast.makeText(context, if (ok) R.string.timetable_export_done else R.string.timetable_export_failed,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun readBackup(uri: Uri) {
        val context = requireContext().applicationContext
        launch {
            val backup = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IOException("Cannot open file")
                withContext(Dispatchers.Default) { TimetableBackup.decode(text) }
            } catch (e: Exception) {
                if (e !is IOException && e !is TimetableBackup.InvalidBackupException) throw e
                if (isAdded) MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.timetable_restore_failed)
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.timetable_restore_backup)
                .setMessage(getString(R.string.timetable_restore_confirm,
                    backup.terms.size, backup.courses.size, backup.tasks.size))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.timetable_restore_action) { _, _ ->
                    launch {
                        repository.restoreBackup(backup)
                        Toast.makeText(context, R.string.timetable_restore_done, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    // ---- Dialog forms -----------------------------------------------------
    private fun showTermDialog(existing: Term?) {
        val ctx = requireContext()
        val layout = formLayout()
        val name = EditText(ctx).apply { hint = getString(R.string.timetable_term_name); setText(existing?.name.orEmpty()) }
        var start = existing?.startDate ?: LocalDate.now()
        var end = existing?.endDate ?: LocalDate.now().plusMonths(4)
        val startButton = Button(ctx).apply {
            text = ctx.getString(R.string.timetable_start_date) + ": " + start.format(DATE_FORMAT)
            setOnClickListener { pickDate(start) { picked -> start = picked; text = ctx.getString(R.string.timetable_start_date) + ": " + picked.format(DATE_FORMAT) } }
        }
        val endButton = Button(ctx).apply {
            text = ctx.getString(R.string.timetable_end_date) + ": " + end.format(DATE_FORMAT)
            setOnClickListener { pickDate(end) { picked -> end = picked; text = ctx.getString(R.string.timetable_end_date) + ": " + picked.format(DATE_FORMAT) } }
        }
        layout.addView(name); layout.addView(startButton); layout.addView(endButton)
        val campuses = data.campuses
        val campusSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                listOf(getString(R.string.timetable_no_university)) + campuses.map { it.name }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(campuses.indexOfFirst { it.id == existing?.campusId } + 1)
        }
        layout.addView(TextView(ctx).apply { text = getString(R.string.timetable_term_university) })
        layout.addView(campusSpinner)
        if (campuses.isEmpty()) layout.addView(TextView(ctx).apply {
            text = getString(R.string.timetable_term_university_hint)
            textSize = 12f
        })
        fun selectedCampusId() = campuses.getOrNull(campusSpinner.selectedItemPosition - 1)?.id

        showForm(if (existing == null) R.string.timetable_new_term else R.string.timetable_edit_term, layout) {
            when {
                name.text.isBlank() -> { toast(R.string.timetable_name_required); false }
                end.isBefore(start) -> { toast(R.string.timetable_invalid_range); false }
                else -> {
                    launch {
                        if (existing == null) {
                            val id = repository.addTerm(name.text.toString(), start, end)
                            activeTermId = id
                            selectedCampusId()?.let { campusId ->
                                repository.updateTerm(Term(id, name.text.toString(), start.toEpochDay(),
                                    end.toEpochDay(), campusId))
                            }
                        } else {
                            repository.updateTerm(existing.copy(name = name.text.toString(),
                                startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay(),
                                campusId = selectedCampusId()))
                            if (existing.campusId != selectedCampusId()) toast(R.string.timetable_locations_regenerate)
                        }
                    }
                    true
                }
            }
        }
    }

    private fun showCourseDialog(existing: Course?) {
        val ctx = requireContext()
        val layout = formLayout()
        val name = EditText(ctx).apply { hint = getString(R.string.timetable_course_name); setText(existing?.name.orEmpty()) }
        val teacher = EditText(ctx).apply { hint = getString(R.string.timetable_teacher); setText(existing?.teacher.orEmpty()) }
        val room = EditText(ctx).apply { hint = getString(R.string.timetable_course_default_room); setText(existing?.room.orEmpty()) }
        val colors = ctx.resources.getIntArray(R.array.defaultCalendarColors).toList()
        val colorSpinner = Spinner(ctx).apply {
            adapter = object : ArrayAdapter<Int>(ctx, android.R.layout.simple_spinner_item, colors) {
                private fun colorText(position: Int, convertView: View?, parent: ViewGroup): TextView {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    val size = (24 * resources.displayMetrics.density).toInt()
                    val dot = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colors[position])
                        setSize(size, size)
                    }
                    view.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                    view.compoundDrawablePadding = size / 2
                    view.text = getString(R.string.timetable_color_n, position + 1)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    colorText(position, convertView, parent)

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    colorText(position, convertView, parent)
            }
            if (existing != null) setSelection(colors.indexOf(existing.color).coerceAtLeast(0))
        }
        layout.addView(name); layout.addView(teacher); layout.addView(room); layout.addView(colorSpinner)

        // Optional date range, e.g. a block course that only meets for a few weeks.
        val term = data.terms.firstOrNull { it.id == activeTermId }
        var rangeStart = existing?.startEpochDay?.let(LocalDate::ofEpochDay) ?: term?.startDate ?: LocalDate.now()
        var rangeEnd = existing?.endEpochDay?.let(LocalDate::ofEpochDay) ?: term?.endDate ?: rangeStart.plusWeeks(6)
        val limited = CheckBox(ctx).apply {
            text = getString(R.string.timetable_course_limited)
            isChecked = existing?.startEpochDay != null || existing?.endEpochDay != null
        }
        val startButton = Button(ctx)
        val endButton = Button(ctx)
        fun refreshRange() {
            startButton.text = getString(R.string.timetable_course_first_day, rangeStart.format(DATE_FORMAT))
            endButton.text = getString(R.string.timetable_course_last_day, rangeEnd.format(DATE_FORMAT))
            val visibility = if (limited.isChecked) View.VISIBLE else View.GONE
            startButton.visibility = visibility
            endButton.visibility = visibility
        }
        refreshRange()
        limited.setOnCheckedChangeListener { _, _ -> refreshRange() }
        startButton.setOnClickListener { pickDate(rangeStart) { rangeStart = it; refreshRange() } }
        endButton.setOnClickListener { pickDate(rangeEnd) { rangeEnd = it; refreshRange() } }
        layout.addView(limited); layout.addView(startButton); layout.addView(endButton)

        showForm(if (existing == null) R.string.timetable_new_course else R.string.timetable_edit_course, layout) {
            if (name.text.isBlank()) { toast(R.string.timetable_name_required); false }
            else if (limited.isChecked && rangeEnd.isBefore(rangeStart)) { toast(R.string.timetable_end_before_start); false }
            else {
                val start = if (limited.isChecked) rangeStart.toEpochDay() else null
                val end = if (limited.isChecked) rangeEnd.toEpochDay() else null
                val course = (existing ?: Course(name = "")).copy(name = name.text.toString(),
                    teacher = teacher.text.toString(), room = room.text.toString(),
                    color = colors[colorSpinner.selectedItemPosition], startEpochDay = start, endEpochDay = end)
                launch {
                    if (existing == null) repository.addCourse(course) else repository.updateCourse(course)
                }
                true
            }
        }
    }

    private fun showRegularSlotDialog(existing: RegularSlot?, linkedIds: Set<Long>) {
        if (linkedIds.isEmpty()) { toast(R.string.timetable_no_courses_in_term); return }
        val ctx = requireContext()
        val layout = formLayout()
        val courses = data.courses.filter { it.id in linkedIds }
        val (courseSpinner, _, weekdaySpinner, startButton, endButton) = slotFields(
            courses, existing?.courseId, existing?.weekday ?: 1,
            existing?.startMinute ?: 9 * 60, existing?.endMinute ?: 10 * 60
        )
        layout.addView(courseSpinner); layout.addView(weekdaySpinner)
        layout.addView(startButton); layout.addView(endButton)
        val place = placeFields(layout, courseSpinner, courses, existing?.buildingId, existing?.room)

        showForm(if (existing == null) R.string.timetable_add_regular_slot else R.string.timetable_edit_slot, layout) {
            val start = startButton.tag as Int
            val end = endButton.tag as Int
            if (end <= start) { toast(R.string.timetable_invalid_time); false }
            else {
                launch {
                    val courseId = courses[courseSpinner.selectedItemPosition].id
                    val slot = RegularSlot(
                        id = existing?.id ?: 0, courseId = courseId, termId = activeTermId,
                        weekday = weekdaySpinner.selectedItemPosition + 1,
                        startMinute = start, endMinute = end,
                        buildingId = place.buildingId(), room = place.room(),
                    )
                    if (existing == null) repository.addRegularSlot(slot)
                    else repository.updateRegularSlot(slot)
                }
                true
            }
        }
    }

    private fun showOneTimeSlotDialog(existing: OneTimeSlot?, linkedIds: Set<Long>) {
        if (linkedIds.isEmpty()) { toast(R.string.timetable_no_courses_in_term); return }
        val ctx = requireContext()
        val layout = formLayout()
        val courses = data.courses.filter { it.id in linkedIds }
        var date = existing?.date ?: LocalDate.now()
        val (courseSpinner, dateButton, _, startButton, endButton) = slotFields(
            courses, existing?.courseId, 1, existing?.startMinute ?: 9 * 60,
            existing?.endMinute ?: 10 * 60
        )
        dateButton.visibility = View.VISIBLE
        dateButton.text = getString(R.string.timetable_date) + ": " + date.format(DATE_FORMAT)
        dateButton.setOnClickListener { pickDate(date) { picked -> date = picked; dateButton.text = getString(R.string.timetable_date) + ": " + picked.format(DATE_FORMAT) } }
        layout.addView(courseSpinner); layout.addView(dateButton)
        layout.addView(startButton); layout.addView(endButton)
        val place = placeFields(layout, courseSpinner, courses, existing?.buildingId, existing?.room)

        showForm(if (existing == null) R.string.timetable_add_one_time_slot else R.string.timetable_edit_slot, layout) {
            val start = startButton.tag as Int
            val end = endButton.tag as Int
            if (end <= start) { toast(R.string.timetable_invalid_time); false }
            else {
                launch {
                    val slot = OneTimeSlot(
                        id = existing?.id ?: 0,
                        courseId = courses[courseSpinner.selectedItemPosition].id,
                        termId = activeTermId, dateEpochDay = date.toEpochDay(),
                        startMinute = start, endMinute = end,
                        buildingId = place.buildingId(), room = place.room(),
                    )
                    if (existing == null) repository.addOneTimeSlot(slot)
                    else repository.updateOneTimeSlot(slot)
                }
                true
            }
        }
    }

    private fun showFreeDayDialog(termId: Long) {
        val ctx = requireContext()
        var date = LocalDate.now()
        val button = Button(ctx).apply {
            text = getString(R.string.timetable_date) + ": " + date.format(DATE_FORMAT)
            setOnClickListener { pickDate(date) { picked -> date = picked; text = getString(R.string.timetable_date) + ": " + picked.format(DATE_FORMAT) } }
        }
        val layout = formLayout().apply { addView(button) }
        showForm(R.string.timetable_add_free_day, layout) {
            launch { repository.addFreeDay(termId, date); toast(R.string.timetable_free_day_added) }
            true
        }
    }

    /** Shared fields for regular and one-off slot dialogs. */
    private fun slotFields(
        courses: List<Course>, selectedCourseId: Long?, weekday: Int, start: Int, end: Int,
    ): Fields {
        val ctx = requireContext()
        val courseSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, courses.map { it.name }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            if (selectedCourseId != null) {
                setSelection(courses.indexOfFirst { it.id == selectedCourseId }.coerceAtLeast(0))
            }
        }
        val weekdaySpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(weekday - 1)
        }
        val dateButton = Button(ctx).apply { visibility = View.GONE }
        val startButton = timeButton(R.string.timetable_start_time, start)
        val endButton = timeButton(R.string.timetable_end_time, end)
        return Fields(courseSpinner, dateButton, weekdaySpinner, startButton, endButton)
    }

    private data class Fields(
        val courseSpinner: Spinner,
        val dateButton: Button,
        val weekdaySpinner: Spinner,
        val startButton: Button,
        val endButton: Button,
    )

    private fun timeButton(labelRes: Int, minute: Int): Button = Button(requireContext()).apply {
        tag = minute
        fun update() { text = "${getString(labelRes)}: ${minutesToTime(tag as Int)}" }
        update()
        setOnClickListener {
            pickTime(tag as Int) { picked -> tag = picked; update() }
        }
    }

    private fun showPreview(preview: GenerationPreview) {
        val message = if (preview.isEmpty) {
            getString(R.string.timetable_preview_empty)
        } else {
            getString(R.string.timetable_preview_summary, preview.toCreate.size,
                preview.toUpdate.size, preview.toDelete.size, preview.userEditedKept.size) +
                if (preview.details.isNotEmpty()) "\n\n" + preview.details.joinToString("\n") else ""
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.timetable_preview_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.timetable_apply) { _, _ ->
                launch {
                    repository.applyGeneration(preview)
                    toast(R.string.timetable_done)
                }
            }
            .show()
    }

    // ---- Small UI helpers -------------------------------------------------
    private fun showForm(titleRes: Int, content: View, onSave: () -> Boolean) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (onSave()) dialog.dismiss()
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.yes) { _, _ -> onYes() }
            .show()
    }

    private fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.timetable_date)
            .setSelection(initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            onPicked(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate())
        }
        picker.show(parentFragmentManager, "timetable_date")
    }

    private fun pickTime(initialMinute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialMinute / 60)
            .setMinute(initialMinute % 60)
            .setTitleText(R.string.timetable_start_time)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(parentFragmentManager, "timetable_time")
    }

    private fun formLayout() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20))
    }

    private fun row() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun addHeading(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setTextColor(resources.getColor(R.color.colorPrimary, null))
            setPadding(0, dp(12), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun addNote(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun addButton(label: String, click: () -> Unit) {
        container.addView(Button(requireContext()).apply {
            text = label
            setOnClickListener { click() }
        })
    }

    private fun iconButton(icon: Int, label: Int, click: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon)
        contentDescription = getString(label)
        background = null
        setOnClickListener { click() }
    }

    private fun addEditableRow(label: String, note: String? = "", onEdit: (() -> Unit)? = null, onDelete: () -> Unit) {
        val row = row()
        row.addView(TextView(requireContext()).apply {
            this.text = label
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onEdit != null) row.addView(iconButton(R.drawable.outline_edit_event, R.string.timetable_edit_slot, onEdit))
        row.addView(iconButton(R.drawable.outline_delete_event, R.string.timetable_delete_slot, onDelete))
        container.addView(row)
        if (!note.isNullOrEmpty()) { /* future: extra detail line */ }
    }

    private fun weekdayName(weekday: Int): String =
        DayOfWeek.of(weekday).getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun launch(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/u", Locale.getDefault())
    }
}
