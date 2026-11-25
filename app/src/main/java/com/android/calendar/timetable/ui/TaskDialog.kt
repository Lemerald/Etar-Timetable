package com.android.calendar.timetable.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.calendar.timetable.data.ClassMeeting
import com.android.calendar.timetable.data.ClassMeetings
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.DueAnchor
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.TimetableRepository
import com.android.calendar.timetable.data.minutesToTime
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import ws.xsoh.etar.R

/** Shared create/edit dialog for course assignments and general to-dos. */
object TaskDialog {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("d/M/u", Locale.getDefault())

    /** Convenience entry point for the toolbar FAB in the To-dos view. */
    @JvmStatic
    fun openFor(activity: FragmentActivity) {
        val repository = TimetableRepository.get(activity)
        activity.lifecycleScope.launch {
            val data = repository.data.first()
            show(activity, repository, data, data.courses)
        }
    }

    fun show(
        activity: FragmentActivity,
        repository: TimetableRepository,
        data: TimetableData,
        courses: List<Course> = data.courses,
        existing: Task? = null,
        defaultDue: LocalDate? = null,
        defaultCourseId: Long? = null,
    ) {
        val ctx: Context = activity
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20))
        }

        val title = EditText(ctx).apply {
            hint = ctx.getString(R.string.todo_task_title)
            setText(existing?.title.orEmpty())
        }
        val note = EditText(ctx).apply {
            hint = ctx.getString(R.string.todo_task_note)
            setText(existing?.note.orEmpty())
        }
        val courseSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                listOf(ctx.getString(R.string.todo_no_course)) + courses.map { it.name }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val initialCourseId = when {
                existing != null -> existing.courseId
                defaultCourseId != null -> defaultCourseId
                else -> lastCourseId(ctx)
            }
            val courseIndex = courses.indexOfFirst { it.id == initialCourseId }
            setSelection(if (courseIndex >= 0) courseIndex + 1 else 0)
        }
        fun selectedCourseId(): Long? =
            if (courseSpinner.selectedItemPosition <= 0) null else courses[courseSpinner.selectedItemPosition - 1].id

        var date: LocalDate = existing?.dueAtMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: defaultDue ?: LocalDate.now()
        var minute: Int = existing?.dueAtMillis?.let {
            val time = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
            time.hour * 60 + time.minute
        } ?: END_OF_DAY_MINUTE

        val noDue = CheckBox(ctx).apply {
            text = ctx.getString(R.string.todo_bucket_no_date)
            // New tasks default to a due date (today); only preserve an explicitly
            // absent due date when editing an existing task.
            isChecked = existing != null && existing.dueAtMillis == null
        }
        val dateButton = Button(ctx)
        val timeButton = Button(ctx)
        fun refreshDue() {
            dateButton.text = ctx.getString(R.string.todo_due_date) + ": " + date.format(DATE_FORMAT)
            timeButton.text = minutesToTime(minute)
            dateButton.isEnabled = !noDue.isChecked
            timeButton.isEnabled = !noDue.isChecked
        }
        refreshDue()
        noDue.setOnCheckedChangeListener { _, _ -> refreshDue() }

        // ---- Due presets relative to the course's class meetings (US-2.5) ----
        val presetSpinner = Spinner(ctx)
        var meetings: List<ClassMeeting> = emptyList()
        var meetingIndex = 0
        var lastAnchor = DueAnchor.BEFORE_CLASS
        var presets: List<DuePreset> = emptyList()
        var presetSelection = 0
        var updatingPresets = false

        fun selectPreset(index: Int) {
            updatingPresets = true
            presetSelection = index
            presetSpinner.setSelection(index, false)
            updatingPresets = false
        }

        fun applyAnchor(anchor: DueAnchor) {
            val due = anchor.resolve(meetings[meetingIndex])
            date = due.toLocalDate()
            minute = due.hour * 60 + due.minute
            lastAnchor = anchor
            noDue.isChecked = false
            refreshDue()
        }

        fun rebuildPresets() {
            presets = buildList {
                add(DuePreset(ctx.getString(R.string.todo_due_preset_custom), null))
                meetings.getOrNull(meetingIndex)?.let { meeting ->
                    DueAnchor.values().forEach { anchor ->
                        add(DuePreset(ctx.getString(anchorLabel(anchor),
                            anchor.resolve(meeting).format(PRESET_FORMAT)), anchor))
                    }
                    if (meetings.size > 1) add(DuePreset(ctx.getString(R.string.todo_due_other_meeting), null, pickOther = true))
                }
            }
            updatingPresets = true
            presetSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, presets.map { it.label }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            updatingPresets = false
            presetSpinner.visibility = if (meetings.isEmpty()) View.GONE else View.VISIBLE
        }

        fun loadMeetings(applyDefault: Boolean) {
            meetings = selectedCourseId()?.let { ClassMeetings.upcoming(data, it, LocalDateTime.now()) }.orEmpty()
            meetingIndex = 0
            rebuildPresets()
            if (applyDefault && meetings.isNotEmpty()) {
                applyAnchor(lastAnchor)
                selectPreset(presets.indexOfFirst { it.anchor == lastAnchor })
            } else {
                selectPreset(0)
            }
        }

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (updatingPresets || position == presetSelection) return
                val preset = presets[position]
                when {
                    preset.anchor != null -> { applyAnchor(preset.anchor); presetSelection = position }
                    preset.pickOther -> {
                        selectPreset(presetSelection) // stay put unless a meeting is picked
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.todo_pick_meeting)
                            .setItems(meetings.map {
                                it.start.format(PRESET_FORMAT) + "–" + it.end.format(TIME_FORMAT)
                            }.toTypedArray()) { _, which ->
                                meetingIndex = which
                                rebuildPresets()
                                applyAnchor(lastAnchor)
                                selectPreset(presets.indexOfFirst { it.anchor == lastAnchor })
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    else -> presetSelection = position // custom: keep current date/time
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        // A new task gets the default preset; an edited task keeps its due time.
        loadMeetings(applyDefault = existing == null)
        var courseInitialized = false
        courseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                // The first callback is the initial selection, already handled above.
                if (!courseInitialized) { courseInitialized = true; return }
                loadMeetings(applyDefault = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        dateButton.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.todo_due_date)
                .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                date = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
                selectPreset(0)
                refreshDue()
            }
            picker.show(activity.supportFragmentManager, "task_date")
        }
        timeButton.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(minute / 60).setMinute(minute % 60)
                .setTitleText(R.string.todo_due_date)
                .build()
            picker.addOnPositiveButtonClickListener {
                minute = picker.hour * 60 + picker.minute
                selectPreset(0)
                refreshDue()
            }
            picker.show(activity.supportFragmentManager, "task_time")
        }

        layout.addView(title)
        layout.addView(note)
        layout.addView(courseSpinner)
        layout.addView(presetSpinner)
        layout.addView(noDue)
        layout.addView(dateButton)
        layout.addView(timeButton)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.todo_new_task else R.string.todo_edit_task)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (title.text.isBlank()) {
                Toast.makeText(ctx, R.string.timetable_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dueMillis = if (noDue.isChecked) null else
                date.atTime(minute / 60, minute % 60)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val courseId = selectedCourseId()
            if (existing == null) rememberCourse(ctx, courseId)
            activity.lifecycleScope.launch {
                if (existing == null) {
                    repository.addTaskSynced(Task(
                        title = title.text.toString(), note = note.text.toString(),
                        courseId = courseId, dueAtMillis = dueMillis,
                    ))
                } else {
                    repository.updateTaskSynced(existing.copy(
                        title = title.text.toString(), note = note.text.toString(),
                        courseId = courseId, dueAtMillis = dueMillis,
                    ))
                }
            }
            dialog.dismiss()
        }
    }

    private class DuePreset(val label: String, val anchor: DueAnchor?, val pickOther: Boolean = false)

    private const val END_OF_DAY_MINUTE = 23 * 60 + 59
    private const val PREFS_NAME = "etar_timetable_prefs"
    private const val KEY_LAST_COURSE = "last_task_course"
    private val PRESET_FORMAT = DateTimeFormatter.ofPattern("EEE d/M HH:mm", Locale.getDefault())
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    private fun anchorLabel(anchor: DueAnchor) = when (anchor) {
        DueAnchor.BEFORE_CLASS -> R.string.todo_due_before_class
        DueAnchor.START_OF_CLASS -> R.string.todo_due_start_of_class
        DueAnchor.END_OF_CLASS -> R.string.todo_due_end_of_class
        DueAnchor.DAY_BEFORE -> R.string.todo_due_day_before
    }

    /** Like Power Planner, new tasks start with the course used last time. */
    private fun lastCourseId(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_COURSE, -1L).takeIf { it != -1L }

    private fun rememberCourse(context: Context, courseId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_COURSE, courseId ?: -1L).apply()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
