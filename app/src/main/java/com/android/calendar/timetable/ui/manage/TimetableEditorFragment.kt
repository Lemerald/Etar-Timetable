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
import com.android.calendar.timetable.io.AutoBackup
import com.android.calendar.timetable.io.TimetableBackup
import com.android.calendar.timetable.reminders.ReminderSettings
import com.android.calendar.timetable.sync.OpenTasksSync
import com.android.calendar.timetable.ui.form.FormPickers
import com.android.calendar.timetable.ui.form.FormSheet
import com.google.android.material.textfield.TextInputEditText
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
        renderCalendarSection()
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
        renderCalendarSection()
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

    /** Which calendar class events and task deadlines are written to. */
    private fun renderCalendarSection() {
        val ctx = requireContext()
        addHeading(getString(R.string.timetable_target_calendar_heading))
        val status = TextView(ctx).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        container.addView(status)
        launch {
            val choices = repository.calendarChoices()
            val current = repository.currentTargetCalendarId()
            if (isAdded) status.text = getString(R.string.timetable_target_calendar_status,
                choices.firstOrNull { it.id == current }?.let(::calendarLabel) ?: "–")
        }
        addButton(getString(R.string.timetable_target_calendar_change)) {
            launch {
                val choices = repository.calendarChoices()
                val current = repository.currentTargetCalendarId()
                if (!isAdded) return@launch
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.timetable_target_calendar_change)
                    .setSingleChoiceItems(choices.map(::calendarLabel).toTypedArray(),
                        choices.indexOfFirst { it.id == current }) { dialog, which ->
                        dialog.dismiss()
                        val choice = choices[which]
                        if (choice.id != current) confirmMove(choice)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun calendarLabel(choice: TimetableRepository.CalendarChoice) =
        if (choice.isOwn) getString(R.string.timetable_target_calendar_own, choice.name)
        else "${choice.name} (${choice.account})"

    private fun confirmMove(choice: TimetableRepository.CalendarChoice) {
        launch {
            val count = repository.managedEventCount()
            if (!isAdded) return@launch
            confirm(getString(R.string.timetable_target_calendar_change),
                getString(R.string.timetable_target_calendar_confirm, count, calendarLabel(choice))) {
                toast(R.string.timetable_target_calendar_moving)
                launch {
                    // Runs NonCancellable inside, so it completes even if the screen is left.
                    repository.moveToCalendar(choice.id)
                    if (isAdded) { toast(R.string.timetable_target_calendar_moved); render() }
                }
            }
        }
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

    /** Address field plus a "check on map" button, inside a form. */
    private fun FormSheet.addressField(initial: String, label: Int): TextInputEditText {
        val address = text(getString(label), initial, multiline = true,
            helper = getString(R.string.timetable_form_address_helper))
        textButton(getString(R.string.timetable_check_on_map), R.drawable.ic_outline_place) {
            val query = address.text.toString().trim()
            if (query.isEmpty()) toast(R.string.timetable_form_enter_address_first) else openOnMap(query)
        }
        return address
    }

    /**
     * Lets the user pick a maps app (geo: URI) or the OpenStreetMap website. Not every
     * maps app supports address search via geo:, so the web search is always offered.
     */
    private fun openOnMap(query: String) {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        val web = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.openstreetmap.org/search?query=" + Uri.encode(query)))
        val chooser = Intent.createChooser(geo, getString(R.string.timetable_check_on_map))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(web))
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.timetable_no_maps_app)
        }
    }

    private fun form(titleRes: Int, onSave: FormSheet.() -> Boolean): FormSheet {
        lateinit var sheet: FormSheet
        sheet = FormSheet(requireContext(), getString(titleRes)) { sheet.onSave() }
        return sheet
    }

    private fun showCampusDialog(existing: Campus?) {
        lateinit var name: TextInputEditText
        lateinit var address: TextInputEditText
        val sheet = form(if (existing == null) R.string.timetable_add_university else R.string.timetable_edit_university) {
            if (!requireText(name, getString(R.string.timetable_name_required))) return@form false
            val campus = (existing ?: Campus(name = "")).copy(
                name = name.text.toString().trim(), address = address.text.toString().trim())
            launch {
                repository.saveCampus(campus)
                if (existing != null && existing.address != campus.address) toast(R.string.timetable_locations_regenerate)
            }
            true
        }
        name = sheet.text(getString(R.string.timetable_university_name), existing?.name.orEmpty(),
            placeholder = getString(R.string.timetable_form_university_example), focus = existing == null)
        address = sheet.addressField(existing?.address.orEmpty(), R.string.timetable_university_address)
        sheet.note(getString(R.string.timetable_form_university_note))
        sheet.show()
    }

    private fun showBuildingDialog(existing: Building?, campusId: Long) {
        lateinit var name: TextInputEditText
        lateinit var address: TextInputEditText
        val sheet = form(if (existing == null) R.string.timetable_add_building else R.string.timetable_edit_building) {
            if (!requireText(name, getString(R.string.timetable_name_required))) return@form false
            val building = (existing ?: Building(campusId = campusId, name = "")).copy(
                name = name.text.toString().trim(), address = address.text.toString().trim())
            launch {
                repository.saveBuilding(building)
                if (existing != null && existing != building) toast(R.string.timetable_locations_regenerate)
            }
            true
        }
        name = sheet.text(getString(R.string.timetable_building_name), existing?.name.orEmpty(),
            placeholder = getString(R.string.timetable_form_building_example), focus = existing == null)
        address = sheet.addressField(existing?.address.orEmpty(), R.string.timetable_building_address)
        val campusName = data.campuses.firstOrNull { it.id == campusId }?.name.orEmpty()
        sheet.note(getString(R.string.timetable_form_building_note, campusName))
        sheet.show()
    }

    /**
     * Building dropdown over all buildings, those of the active term's university first.
     * Entry 0 is "none" (relabelled to "Same as course (X)" for slots).
     */
    private inner class BuildingPicker(sheet: FormSheet, noneLabel: String, initialBuildingId: Long?,
                                       onChange: () -> Unit = {}) {
        private val term = data.terms.firstOrNull { it.id == activeTermId }
        private val campusNames = data.campuses.associate { it.id to it.name }
        val buildings = data.buildings.sortedWith(compareBy({ it.campusId != term?.campusId },
            { campusNames[it.campusId] }, { it.name }))
        val dropdown: FormSheet.Dropdown? = if (buildings.isEmpty()) null else sheet.dropdown(
            getString(R.string.timetable_building),
            listOf(noneLabel) + buildings.map {
                if (it.campusId == term?.campusId) it.name else "${campusNames[it.campusId]} · ${it.name}"
            },
            buildings.indexOfFirst { it.id == initialBuildingId } + 1,
        ) { onChange() }

        fun buildingId(): Long? = dropdown?.let { buildings.getOrNull(it.selected - 1)?.id }
        fun setNoneLabel(label: String) { dropdown?.relabel(0, label) }
    }

    private class PlaceFields(val buildingId: () -> Long?, val room: () -> String?, val refresh: () -> Unit)

    /**
     * "Where" section of a slot form: building + room, with a live preview of the
     * resulting calendar location. [course] gives the currently selected course.
     */
    private fun FormSheet.placeFields(course: () -> Course?, initialBuildingId: Long?, initialRoom: String?): PlaceFields {
        section(getString(R.string.timetable_form_where))
        lateinit var refresh: () -> Unit
        val picker = BuildingPicker(this, getString(R.string.timetable_no_building), initialBuildingId) { refresh() }
        val room = text(getString(R.string.timetable_room), initialRoom.orEmpty(), capitalize = false)
        val roomLayout = room.parent.parent as com.google.android.material.textfield.TextInputLayout
        val preview = note("")
        fun roomText() = room.text.toString().trim().ifEmpty { null }
        refresh = {
            val selected = course()
            val courseBuilding = data.buildings.firstOrNull { it.id == selected?.buildingId }
            picker.setNoneLabel(courseBuilding?.let { getString(R.string.timetable_building_same_as_course, it.name) }
                ?: getString(R.string.timetable_no_building))
            roomLayout.helperText = selected?.room?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.timetable_form_room_course_default, it) }
            val location = selected?.let { data.placeOf(activeTermId, it.id, picker.buildingId(), roomText()).eventLocation }
            preview.text = getString(R.string.timetable_location_preview,
                location?.ifEmpty { null } ?: getString(R.string.timetable_no_location))
        }
        room.doAfterTextChanged { refresh() }
        refresh()
        return PlaceFields(picker::buildingId, ::roomText, refresh)
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

    private val autoBackupFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                AutoBackup.enable(requireContext(), tree)
                toast(R.string.timetable_auto_backup_enabled)
                render()
            }
        }

    private fun renderBackupSection() {
        addHeading(getString(R.string.timetable_backup_heading))
        addNote(getString(R.string.timetable_backup_note))

        val ctx = requireContext()
        val folder = AutoBackup.folderName(ctx)
        if (folder == null) {
            addNote(getString(R.string.timetable_auto_backup_off))
            addButton(getString(R.string.timetable_auto_backup_choose)) { autoBackupFolderLauncher.launch(null) }
        } else {
            val last = AutoBackup.lastSuccess(ctx).takeIf { it > 0 }?.let {
                Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d/M HH:mm", Locale.getDefault()))
            } ?: "–"
            addNote(getString(R.string.timetable_auto_backup_status, folder, last, AutoBackup.KEEP) +
                (AutoBackup.lastError(ctx)?.let { "\n" + getString(R.string.timetable_auto_backup_error, it) } ?: ""))
            addButton(getString(R.string.timetable_auto_backup_now)) {
                launch {
                    val ok = AutoBackup.backup(ctx)
                    toast(if (ok) R.string.timetable_export_done else R.string.timetable_export_failed)
                    if (isAdded) render()
                }
            }
            addButton(getString(R.string.timetable_auto_backup_change)) { autoBackupFolderLauncher.launch(null) }
            addButton(getString(R.string.timetable_auto_backup_disable)) { AutoBackup.disable(ctx); render() }
        }

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
        var start = existing?.startDate ?: LocalDate.now()
        var end = existing?.endDate ?: LocalDate.now().plusMonths(4)
        lateinit var name: TextInputEditText
        lateinit var endPicker: FormSheet.Picker
        var campus: FormSheet.Dropdown? = null
        val campuses = data.campuses
        fun selectedCampusId() = campus?.let { campuses.getOrNull(it.selected - 1)?.id }
        val sheet = form(if (existing == null) R.string.timetable_new_term else R.string.timetable_edit_term) {
            if (!requireText(name, getString(R.string.timetable_name_required))) return@form false
            if (end.isBefore(start)) { endPicker.setError(getString(R.string.timetable_invalid_range)); return@form false }
            launch {
                if (existing == null) {
                    val id = repository.addTerm(name.text.toString().trim(), start, end)
                    activeTermId = id
                    selectedCampusId()?.let { campusId ->
                        repository.updateTerm(Term(id, name.text.toString().trim(), start.toEpochDay(),
                            end.toEpochDay(), campusId))
                    }
                } else {
                    repository.updateTerm(existing.copy(name = name.text.toString().trim(),
                        startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay(),
                        campusId = selectedCampusId()))
                    if (existing.campusId != selectedCampusId()) toast(R.string.timetable_locations_regenerate)
                }
            }
            true
        }
        name = sheet.text(getString(R.string.timetable_term_name), existing?.name.orEmpty(),
            placeholder = getString(R.string.timetable_form_term_example), focus = existing == null)
        sheet.section(getString(R.string.timetable_form_dates))
        sheet.row {
            sheet.picker(getString(R.string.timetable_start_date), R.drawable.ic_baseline_calendar_today,
                FormPickers.formatDate(start)) { p ->
                pickDate(start) { start = it; p.value = FormPickers.formatDate(it) }
            }
            endPicker = sheet.picker(getString(R.string.timetable_end_date), null, FormPickers.formatDate(end)) { p ->
                pickDate(end) { end = it; p.value = FormPickers.formatDate(it); p.setError(null) }
            }
        }
        sheet.section(getString(R.string.timetable_term_university))
        if (campuses.isEmpty()) {
            sheet.note(getString(R.string.timetable_term_university_hint))
        } else {
            campus = sheet.dropdown(getString(R.string.timetable_term_university),
                listOf(getString(R.string.timetable_no_university)) + campuses.map { it.name },
                campuses.indexOfFirst { it.id == existing?.campusId } + 1,
                helper = getString(R.string.timetable_form_term_university_helper))
        }
        sheet.show()
    }

    private fun showCourseDialog(existing: Course?) {
        lateinit var name: TextInputEditText
        lateinit var teacher: TextInputEditText
        lateinit var room: TextInputEditText
        lateinit var colorPicker: ColorPicker
        lateinit var buildingPicker: BuildingPicker
        lateinit var limited: com.google.android.material.materialswitch.MaterialSwitch
        lateinit var lastDayPicker: FormSheet.Picker
        val term = data.terms.firstOrNull { it.id == activeTermId }
        var rangeStart = existing?.startEpochDay?.let(LocalDate::ofEpochDay) ?: term?.startDate ?: LocalDate.now()
        var rangeEnd = existing?.endEpochDay?.let(LocalDate::ofEpochDay) ?: term?.endDate ?: rangeStart.plusWeeks(6)

        val sheet = form(if (existing == null) R.string.timetable_new_course else R.string.timetable_edit_course) {
            if (!requireText(name, getString(R.string.timetable_name_required))) return@form false
            if (limited.isChecked && rangeEnd.isBefore(rangeStart)) {
                lastDayPicker.setError(getString(R.string.timetable_end_before_start)); return@form false
            }
            val course = (existing ?: Course(name = "")).copy(name = name.text.toString().trim(),
                teacher = teacher.text.toString().trim(), room = room.text.toString().trim(),
                color = colorPicker.selected,
                startEpochDay = if (limited.isChecked) rangeStart.toEpochDay() else null,
                endEpochDay = if (limited.isChecked) rangeEnd.toEpochDay() else null,
                buildingId = buildingPicker.buildingId())
            launch {
                if (existing == null) repository.addCourse(course) else repository.updateCourse(course)
                if (existing != null && (existing.buildingId != course.buildingId || existing.room != course.room)) {
                    toast(R.string.timetable_locations_regenerate)
                }
            }
            true
        }
        name = sheet.text(getString(R.string.timetable_course_name), existing?.name.orEmpty(),
            placeholder = getString(R.string.timetable_form_course_example), focus = existing == null)
        teacher = sheet.text(getString(R.string.timetable_teacher), existing?.teacher.orEmpty(),
            helper = getString(R.string.timetable_form_optional))

        sheet.section(getString(R.string.timetable_form_where))
        buildingPicker = BuildingPicker(sheet, getString(R.string.timetable_no_building), existing?.buildingId)
        room = sheet.text(getString(R.string.timetable_room), existing?.room.orEmpty(), capitalize = false,
            helper = getString(R.string.timetable_form_course_room_helper))
        if (buildingPicker.buildings.isEmpty()) sheet.note(getString(R.string.timetable_course_building_hint))

        sheet.section(getString(R.string.timetable_color))
        val usedColors = data.courses.filter { it.id != existing?.id }.map { it.color }.toSet()
        colorPicker = ColorPicker(existing?.color ?: COURSE_COLORS.firstOrNull { it !in usedColors } ?: COURSE_COLORS[0])
        colorPicker.view.setPadding(0, sheet.dp(8), 0, 0)
        sheet.add(colorPicker.view)

        // Optional date range, e.g. a block course that only meets for a few weeks.
        sheet.section(getString(R.string.timetable_form_course_period))
        lateinit var rangeRow: View
        limited = sheet.switch(getString(R.string.timetable_course_limited),
            existing?.startEpochDay != null || existing?.endEpochDay != null) { on ->
            rangeRow.visibility = if (on) View.VISIBLE else View.GONE
        }
        rangeRow = sheet.row {
            sheet.picker(getString(R.string.timetable_form_first_day), R.drawable.ic_baseline_calendar_today,
                FormPickers.formatDate(rangeStart)) { p ->
                pickDate(rangeStart) { rangeStart = it; p.value = FormPickers.formatDate(it) }
            }
            lastDayPicker = sheet.picker(getString(R.string.timetable_form_last_day), null,
                FormPickers.formatDate(rangeEnd)) { p ->
                pickDate(rangeEnd) { rangeEnd = it; p.value = FormPickers.formatDate(it); p.setError(null) }
            }
        }
        rangeRow.visibility = if (limited.isChecked) View.VISIBLE else View.GONE
        sheet.show()
    }

    /** Course dropdown for slot forms; returns it and a getter for the selected course. */
    private fun FormSheet.courseDropdown(courses: List<Course>, selectedId: Long?, onChange: () -> Unit): FormSheet.Dropdown =
        dropdown(getString(R.string.timetable_form_course), courses.map { it.name },
            courses.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)) { onChange() }

    /** Start and end time side by side; [times] holds and receives the minutes. */
    private fun FormSheet.timeRow(times: IntArray): FormSheet.Picker {
        lateinit var endPicker: FormSheet.Picker
        row {
            picker(getString(R.string.timetable_start_time), R.drawable.ic_form_schedule,
                FormPickers.formatTime(times[0])) { p ->
                pickTime(times[0], R.string.timetable_start_time) { picked ->
                    // Keep the duration when the start moves.
                    val duration = times[1] - times[0]
                    times[0] = picked
                    p.value = FormPickers.formatTime(picked)
                    if (duration > 0 && picked + duration < 24 * 60) {
                        times[1] = picked + duration
                        endPicker.value = FormPickers.formatTime(times[1])
                    }
                    endPicker.setError(null)
                }
            }
            endPicker = picker(getString(R.string.timetable_end_time), null, FormPickers.formatTime(times[1])) { p ->
                pickTime(times[1], R.string.timetable_end_time) { picked ->
                    times[1] = picked; p.value = FormPickers.formatTime(picked); p.setError(null)
                }
            }
        }
        return endPicker
    }

    private fun showRegularSlotDialog(existing: RegularSlot?, linkedIds: Set<Long>) {
        if (linkedIds.isEmpty()) { toast(R.string.timetable_no_courses_in_term); return }
        val courses = data.courses.filter { it.id in linkedIds }
        val times = intArrayOf(existing?.startMinute ?: 9 * 60, existing?.endMinute ?: 10 * 60 + 30)
        lateinit var course: FormSheet.Dropdown
        lateinit var days: FormSheet.Segments
        lateinit var endPicker: FormSheet.Picker
        lateinit var place: PlaceFields
        lateinit var dayError: TextView

        val sheet = form(if (existing == null) R.string.timetable_add_regular_slot else R.string.timetable_edit_slot) {
            val selectedDays = days.selected.map { it + 1 }.sorted()
            if (selectedDays.isEmpty()) { dayError.visibility = View.VISIBLE; return@form false }
            if (times[1] <= times[0]) { endPicker.setError(getString(R.string.timetable_invalid_time)); return@form false }
            val courseId = courses[course.selected].id
            launch {
                selectedDays.forEachIndexed { index, weekday ->
                    // Editing: the first chosen day updates this slot, further days add new ones.
                    val id = if (index == 0) existing?.id ?: 0 else 0
                    val slot = RegularSlot(id = id, courseId = courseId, termId = activeTermId,
                        weekday = weekday, startMinute = times[0], endMinute = times[1],
                        buildingId = place.buildingId(), room = place.room())
                    if (id == 0L) repository.addRegularSlot(slot) else repository.updateRegularSlot(slot)
                }
            }
            true
        }
        course = sheet.courseDropdown(courses, existing?.courseId) { place.refresh() }
        sheet.section(getString(R.string.timetable_form_when))
        val defaultDay = existing?.weekday ?: LocalDate.now().dayOfWeek.value.coerceAtMost(5)
        days = sheet.segments(DayOfWeek.entries.map {
            it.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()).take(2)
        }, setOf(defaultDay - 1), multi = true) { dayError.visibility = View.GONE }
        dayError = sheet.note(getString(R.string.timetable_form_pick_day)).apply {
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorError))
            visibility = View.GONE
        }
        if (existing == null) sheet.note(getString(R.string.timetable_form_days_hint))
        endPicker = sheet.timeRow(times)
        place = sheet.placeFields({ courses.getOrNull(course.selected) }, existing?.buildingId, existing?.room)
        sheet.show()
    }

    private fun showOneTimeSlotDialog(existing: OneTimeSlot?, linkedIds: Set<Long>) {
        if (linkedIds.isEmpty()) { toast(R.string.timetable_no_courses_in_term); return }
        val courses = data.courses.filter { it.id in linkedIds }
        var date = existing?.date ?: LocalDate.now()
        val times = intArrayOf(existing?.startMinute ?: 9 * 60, existing?.endMinute ?: 10 * 60 + 30)
        lateinit var course: FormSheet.Dropdown
        lateinit var endPicker: FormSheet.Picker
        lateinit var place: PlaceFields

        val sheet = form(if (existing == null) R.string.timetable_add_one_time_slot else R.string.timetable_edit_slot) {
            if (times[1] <= times[0]) { endPicker.setError(getString(R.string.timetable_invalid_time)); return@form false }
            launch {
                val slot = OneTimeSlot(id = existing?.id ?: 0, courseId = courses[course.selected].id,
                    termId = activeTermId, dateEpochDay = date.toEpochDay(),
                    startMinute = times[0], endMinute = times[1],
                    buildingId = place.buildingId(), room = place.room())
                if (existing == null) repository.addOneTimeSlot(slot) else repository.updateOneTimeSlot(slot)
            }
            true
        }
        course = sheet.courseDropdown(courses, existing?.courseId) { place.refresh() }
        sheet.section(getString(R.string.timetable_form_when))
        sheet.picker(getString(R.string.timetable_date), R.drawable.ic_baseline_calendar_today,
            FormPickers.formatDate(date)) { p ->
            pickDate(date) { date = it; p.value = FormPickers.formatDate(it) }
        }
        endPicker = sheet.timeRow(times)
        place = sheet.placeFields({ courses.getOrNull(course.selected) }, existing?.buildingId, existing?.room)
        sheet.show()
    }

    private fun showFreeDayDialog(termId: Long) {
        // A free day is just a date: go straight to the date picker.
        pickDate(LocalDate.now(), R.string.timetable_add_free_day) { date ->
            launch { repository.addFreeDay(termId, date); toast(R.string.timetable_free_day_added) }
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
    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.yes) { _, _ -> onYes() }
            .show()
    }

    private fun pickDate(initial: LocalDate, titleRes: Int = R.string.timetable_date, onPicked: (LocalDate) -> Unit) =
        FormPickers.pickDate(parentFragmentManager, getString(titleRes), initial, onPicked)

    private fun pickTime(initialMinute: Int, titleRes: Int = R.string.timetable_start_time, onPicked: (Int) -> Unit) =
        FormPickers.pickTime(parentFragmentManager, getString(titleRes), initialMinute, onPicked)

    /** Grid of colour swatches; the selected one gets a ring. */
    private inner class ColorPicker(initial: Int) {
        // An existing colour from before this palette stays selectable.
        private val colors = if (initial in COURSE_COLORS) COURSE_COLORS else listOf(initial) + COURSE_COLORS
        var selected = initial
            private set
        private val swatches = mutableListOf<View>()
        val view = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        init {
            colors.chunked(6).forEach { chunk ->
                val line = row()
                chunk.forEach { color ->
                    val swatch = View(requireContext()).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                        tag = color
                        contentDescription = String.format("#%06X", color and 0xFFFFFF)
                        setOnClickListener { selected = color; refresh() }
                    }
                    swatches += swatch
                    // Equal-width cells keep the circles round and evenly spread.
                    line.addView(android.widget.FrameLayout(requireContext()).apply { addView(swatch) },
                        LinearLayout.LayoutParams(0, dp(52), 1f))
                }
                repeat(6 - chunk.size) {
                    line.addView(View(requireContext()), LinearLayout.LayoutParams(0, dp(52), 1f))
                }
                view.addView(line)
            }
            refresh()
        }

        private fun refresh() {
            val ring = TextView(requireContext()).currentTextColor
            swatches.forEach { swatch ->
                val color = swatch.tag as Int
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selected) setStroke(dp(3), ring)
                }
            }
        }
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

        /** Clearly distinct course colours (hue circle, then neutrals), readable with white text. */
        val COURSE_COLORS = listOf(
            0xFFD32F2F, 0xFFE64A19, 0xFFF57C00, 0xFFF9A825, 0xFF9E9D24, 0xFF689F38,
            0xFF2E7D32, 0xFF00897B, 0xFF0097A7, 0xFF0288D1, 0xFF1565C0, 0xFF3949AB,
            0xFF5E35B1, 0xFF8E24AA, 0xFFC2185B, 0xFF795548, 0xFF546E7A, 0xFF212121,
        ).map { it.toInt() }
    }
}
