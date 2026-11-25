package com.android.calendar.timetable.ui

import android.app.AlertDialog
import android.content.Context
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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.calendar.timetable.data.ChecklistItem
import com.android.calendar.timetable.data.ClassMeeting
import com.android.calendar.timetable.data.ClassMeetings
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.DueAnchor
import com.android.calendar.timetable.data.RepeatRule
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TaskKind
import com.android.calendar.timetable.data.TaskPriority
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
        val kindSpinner = simpleSpinner(ctx, listOf(ctx.getString(R.string.todo_kind_task),
            ctx.getString(R.string.todo_kind_exam))).apply { setSelection(if (existing?.isExam == true) 1 else 0) }
        fun isExam() = kindSpinner.selectedItemPosition == 1

        val prioritySpinner = simpleSpinner(ctx, PRIORITY_LABELS.map { ctx.getString(it) }).apply {
            setSelection(existing?.priority?.coerceIn(0, PRIORITY_LABELS.size - 1) ?: 0)
        }

        val existingRule = RepeatRule.parse(existing?.repeatRule)
        val repeatOptions: MutableList<RepeatRule?> = REPEAT_OPTIONS.toMutableList()
        var repeatIndex = repeatOptions.indexOfFirst {
            it?.frequency == existingRule?.frequency && it?.interval == existingRule?.interval
        }
        if (repeatIndex < 0) { repeatOptions += existingRule; repeatIndex = repeatOptions.size - 1 }
        val repeatSpinner = simpleSpinner(ctx, repeatOptions.map { repeatLabel(ctx, it) }).apply {
            setSelection(repeatIndex)
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
        var endMinute: Int = existing?.endAtMillis?.let {
            val time = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
            time.hour * 60 + time.minute
        } ?: (minute + DEFAULT_EXAM_MINUTES).coerceAtMost(END_OF_DAY_MINUTE)
        val endButton = Button(ctx)

        val noDue = CheckBox(ctx).apply {
            text = ctx.getString(R.string.todo_bucket_no_date)
            // New tasks default to a due date (today); only preserve an explicitly
            // absent due date when editing an existing task.
            isChecked = existing != null && existing.dueAtMillis == null
        }
        val dateButton = Button(ctx)
        val timeButton = Button(ctx)
        fun refreshDue() {
            val exam = isExam()
            dateButton.text = ctx.getString(if (exam) R.string.todo_exam_date else R.string.todo_due_date) +
                ": " + date.format(DATE_FORMAT)
            timeButton.text = if (exam) ctx.getString(R.string.todo_exam_start) + ": " + minutesToTime(minute)
                else minutesToTime(minute)
            endButton.text = ctx.getString(R.string.todo_exam_end) + ": " + minutesToTime(endMinute)
            val enabled = exam || !noDue.isChecked
            dateButton.isEnabled = enabled
            timeButton.isEnabled = enabled
            noDue.visibility = if (exam) View.GONE else View.VISIBLE
            endButton.visibility = if (exam) View.VISIBLE else View.GONE
            prioritySpinner.visibility = if (exam) View.GONE else View.VISIBLE
            repeatSpinner.visibility = if (exam) View.GONE else View.VISIBLE
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

        fun applyDuringClass() {
            val meeting = meetings[meetingIndex]
            date = meeting.date
            minute = meeting.startMinute
            endMinute = meeting.endMinute
            refreshDue()
        }

        fun rebuildPresets() {
            presets = buildList {
                add(DuePreset(ctx.getString(R.string.todo_due_preset_custom), null))
                meetings.getOrNull(meetingIndex)?.let { meeting ->
                    if (isExam()) {
                        add(DuePreset(ctx.getString(R.string.todo_exam_during_class,
                            meeting.start.format(PRESET_FORMAT) + "–" + meeting.end.format(TIME_FORMAT)),
                            null, duringClass = true))
                    } else DueAnchor.values().forEach { anchor ->
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

        /** Applies the default preset for the current meeting: before class, or during class for exams. */
        fun applyMeetingDefault() {
            if (isExam()) {
                applyDuringClass()
                selectPreset(presets.indexOfFirst { it.duringClass })
            } else {
                applyAnchor(lastAnchor)
                selectPreset(presets.indexOfFirst { it.anchor == lastAnchor })
            }
        }

        fun loadMeetings(applyDefault: Boolean) {
            meetings = selectedCourseId()?.let { ClassMeetings.upcoming(data, it, LocalDateTime.now()) }.orEmpty()
            meetingIndex = 0
            rebuildPresets()
            if (applyDefault && meetings.isNotEmpty()) {
                applyMeetingDefault()
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
                    preset.duringClass -> { applyDuringClass(); presetSelection = position }
                    preset.pickOther -> {
                        selectPreset(presetSelection) // stay put unless a meeting is picked
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.todo_pick_meeting)
                            .setItems(meetings.map {
                                it.start.format(PRESET_FORMAT) + "–" + it.end.format(TIME_FORMAT)
                            }.toTypedArray()) { _, which ->
                                meetingIndex = which
                                rebuildPresets()
                                applyMeetingDefault()
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
        var kindInitialized = false
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!kindInitialized) { kindInitialized = true; return }
                if (isExam() && minute == END_OF_DAY_MINUTE) {
                    minute = DEFAULT_EXAM_START
                    endMinute = DEFAULT_EXAM_START + DEFAULT_EXAM_MINUTES
                }
                rebuildPresets()
                if (meetings.isNotEmpty()) applyMeetingDefault() else selectPreset(0)
                refreshDue()
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        endButton.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(endMinute / 60).setMinute(endMinute % 60)
                .setTitleText(R.string.todo_exam_end)
                .build()
            picker.addOnPositiveButtonClickListener {
                endMinute = picker.hour * 60 + picker.minute
                selectPreset(0)
                refreshDue()
            }
            picker.show(activity.supportFragmentManager, "exam_end")
        }

        // ---- Checklist editor ----
        val checklistContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun addChecklistRow(text: String, done: Boolean): EditText {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = done
            }
            val edit = EditText(ctx).apply {
                setText(text)
                hint = ctx.getString(R.string.todo_checklist_item_hint)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(edit)
            row.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.outline_delete_event)
                contentDescription = ctx.getString(R.string.todo_checklist_remove)
                background = null
                setOnClickListener { checklistContainer.removeView(row) }
            })
            checklistContainer.addView(row)
            return edit
        }
        existing?.let { task -> data.checklistFor(task.id).forEach { addChecklistRow(it.text, it.done) } }
        val addChecklistButton = Button(ctx).apply {
            text = ctx.getString(R.string.todo_checklist_add)
            setOnClickListener { addChecklistRow("", false).requestFocus() }
        }
        fun checklistItems(): List<ChecklistItem> = (0 until checklistContainer.childCount).mapNotNull { index ->
            val row = checklistContainer.getChildAt(index) as LinearLayout
            val text = (row.getChildAt(0) as EditText).text.toString().trim()
            if (text.isEmpty()) null else ChecklistItem(taskId = 0, text = text, done = row.tag as Boolean)
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

        refreshDue()
        layout.addView(title)
        layout.addView(kindSpinner)
        layout.addView(note)
        layout.addView(courseSpinner)
        layout.addView(presetSpinner)
        layout.addView(noDue)
        layout.addView(dateButton)
        layout.addView(timeButton)
        layout.addView(endButton)
        layout.addView(prioritySpinner)
        layout.addView(repeatSpinner)
        layout.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.todo_checklist)
            setPadding(0, dp(ctx, 12), 0, 0)
        })
        layout.addView(checklistContainer)
        layout.addView(addChecklistButton)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.todo_new_task else R.string.todo_edit_task)
            .setView(ScrollView(ctx).apply { addView(layout) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (title.text.isBlank()) {
                Toast.makeText(ctx, R.string.timetable_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val exam = isExam()
            if (exam && endMinute <= minute) {
                Toast.makeText(ctx, R.string.todo_exam_end_before_start, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fun millis(minuteOfDay: Int) = date.atTime(minuteOfDay / 60, minuteOfDay % 60)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dueMillis = if (!exam && noDue.isChecked) null else millis(minute)
            // Weekly sheets etc. stop at the end of the term the task falls in.
            val rule = repeatOptions[repeatSpinner.selectedItemPosition]
                ?.takeIf { !exam && dueMillis != null }
                ?.let { it.copy(until = it.until ?: termEndFor(data, date)) }
            val courseId = selectedCourseId()
            if (existing == null) rememberCourse(ctx, courseId)
            val items = checklistItems()
            fun Task.withFields() = copy(
                title = title.text.toString().trim(), note = note.text.toString(),
                courseId = courseId, dueAtMillis = dueMillis,
                kind = if (exam) TaskKind.EXAM else TaskKind.TASK,
                endAtMillis = if (exam) millis(endMinute) else null,
                priority = if (exam) TaskPriority.NONE else prioritySpinner.selectedItemPosition,
                repeatRule = rule?.format(),
            )
            activity.lifecycleScope.launch {
                val taskId = if (existing == null) {
                    repository.addTaskSynced(Task(title = "").withFields())
                } else {
                    repository.updateTaskSynced(existing.withFields())
                    existing.id
                }
                repository.setChecklist(taskId, items)
            }
            dialog.dismiss()
        }
    }

    private class DuePreset(
        val label: String,
        val anchor: DueAnchor?,
        val pickOther: Boolean = false,
        val duringClass: Boolean = false,
    )

    private const val DEFAULT_EXAM_START = 9 * 60
    private const val DEFAULT_EXAM_MINUTES = 90
    private val PRIORITY_LABELS = listOf(R.string.todo_priority_none, R.string.todo_priority_low,
        R.string.todo_priority_medium, R.string.todo_priority_high)
    private val REPEAT_OPTIONS = listOf(
        null,
        RepeatRule(RepeatRule.Frequency.DAILY),
        RepeatRule(RepeatRule.Frequency.WEEKLY),
        RepeatRule(RepeatRule.Frequency.WEEKLY, 2),
        RepeatRule(RepeatRule.Frequency.MONTHLY),
    )

    private fun repeatLabel(context: Context, rule: RepeatRule?): String = when {
        rule == null -> context.getString(R.string.todo_repeat_none)
        rule.interval == 1 && rule.frequency == RepeatRule.Frequency.DAILY -> context.getString(R.string.todo_repeat_daily)
        rule.interval == 1 && rule.frequency == RepeatRule.Frequency.WEEKLY -> context.getString(R.string.todo_repeat_weekly)
        rule.interval == 2 && rule.frequency == RepeatRule.Frequency.WEEKLY -> context.getString(R.string.todo_repeat_biweekly)
        rule.interval == 1 && rule.frequency == RepeatRule.Frequency.MONTHLY -> context.getString(R.string.todo_repeat_monthly)
        else -> context.getString(R.string.todo_repeat_custom, rule.format())
    }

    private fun termEndFor(data: TimetableData, date: LocalDate): LocalDate? =
        data.terms.filter { date.toEpochDay() in it.startEpochDay..it.endEpochDay }.maxOfOrNull { it.endDate }

    private fun simpleSpinner(context: Context, labels: List<String>) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

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
