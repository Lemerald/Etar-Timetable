package com.android.calendar.timetable.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.android.calendar.timetable.ui.form.FormPickers
import com.android.calendar.timetable.ui.form.FormSheet
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ws.xsoh.etar.R

/** Shared create/edit dialog for course assignments and general to-dos. */
object TaskDialog {

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
        val fm = activity.supportFragmentManager

        // ---- State ----
        var exam = existing?.isExam == true
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
        var noDue = existing != null && existing.dueAtMillis == null && !exam
        var meetings: List<ClassMeeting> = emptyList()
        var meetingIndex = 0
        var lastAnchor = DueAnchor.BEFORE_CLASS

        val existingRule = RepeatRule.parse(existing?.repeatRule)
        val repeatOptions: MutableList<RepeatRule?> = REPEAT_OPTIONS.toMutableList()
        var repeatIndex = repeatOptions.indexOfFirst {
            it?.frequency == existingRule?.frequency && it?.interval == existingRule?.interval
        }
        if (repeatIndex < 0) { repeatOptions += existingRule; repeatIndex = repeatOptions.size - 1 }
        val existingChecklist = existing?.let { data.checklistFor(it.id) }.orEmpty()

        // ---- Views (assigned below; the save callback runs only after show) ----
        lateinit var kind: FormSheet.Segments
        lateinit var title: TextInputEditText
        lateinit var course: FormSheet.Dropdown
        lateinit var note: TextInputEditText
        lateinit var priority: FormSheet.Segments
        lateinit var repeat: FormSheet.Dropdown
        lateinit var checklist: LinearLayout
        lateinit var endPicker: FormSheet.Picker

        fun selectedCourseId(): Long? = courses.getOrNull(course.selected - 1)?.id
        var onCourseChange: () -> Unit = {}

        lateinit var sheet: FormSheet
        sheet = FormSheet(ctx, ctx.getString(if (existing == null) R.string.todo_new_task else R.string.todo_edit_task)) {
            if (!sheet.requireText(title, ctx.getString(R.string.timetable_name_required))) return@FormSheet false
            if (exam && endMinute <= minute) {
                endPicker.setError(ctx.getString(R.string.todo_exam_end_before_start)); return@FormSheet false
            }
            fun millis(minuteOfDay: Int) = date.atTime(minuteOfDay / 60, minuteOfDay % 60)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dueMillis = if (!exam && noDue) null else millis(minute)
            // Weekly sheets etc. stop at the end of the term the task falls in.
            val rule = repeatOptions[repeat.selected]
                ?.takeIf { !exam && dueMillis != null }
                ?.let { it.copy(until = it.until ?: termEndFor(data, date)) }
            val courseId = selectedCourseId()
            if (existing == null) rememberCourse(ctx, courseId)
            val items = checklistItems(checklist)
            fun Task.withFields() = copy(
                title = title.text.toString().trim(), note = note.text.toString().trim(),
                courseId = courseId, dueAtMillis = dueMillis,
                kind = if (exam) TaskKind.EXAM else TaskKind.TASK,
                endAtMillis = if (exam) millis(endMinute) else null,
                priority = if (exam) TaskPriority.NONE else priority.first,
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
            true
        }

        // ---- What ----
        kind = sheet.segments(listOf(ctx.getString(R.string.todo_kind_task), ctx.getString(R.string.todo_kind_exam)),
            setOf(if (exam) 1 else 0))
        title = sheet.text(ctx.getString(R.string.todo_form_title), existing?.title.orEmpty(), focus = existing == null)
        val initialCourseId = when {
            existing != null -> existing.courseId
            defaultCourseId != null -> defaultCourseId
            else -> lastCourseId(ctx)
        }
        course = sheet.dropdown(ctx.getString(R.string.todo_task_course),
            listOf(ctx.getString(R.string.todo_no_course)) + courses.map { it.name },
            courses.indexOfFirst { it.id == initialCourseId } + 1) { onCourseChange() }

        // ---- When ----
        val whenHeading = sheet.section(ctx.getString(R.string.todo_form_due))
        // "Next class: Mon 30 Nov, 09:00–10:30   Change"
        val meetingRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sheet.add(meetingRow)
        lateinit var meetingLine: android.widget.TextView
        lateinit var otherMeeting: View
        sheet.inside(meetingRow) {
            meetingLine = sheet.note("")
            otherMeeting = sheet.textButton(ctx.getString(R.string.todo_form_change)) { }
        }
        meetingLine.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        meetingLine.setPadding(sheet.dp(4), 0, 0, 0)
        val presetChips = sheet.chips()
        lateinit var datePicker: FormSheet.Picker
        lateinit var timePicker: FormSheet.Picker
        lateinit var dueFields: View
        lateinit var endRow: View
        dueFields = sheet.group {
            sheet.row {
                datePicker = sheet.picker(ctx.getString(R.string.todo_form_date), R.drawable.ic_baseline_calendar_today,
                    FormPickers.formatDate(date)) { }
                timePicker = sheet.picker(ctx.getString(R.string.todo_form_time), R.drawable.ic_form_schedule,
                    FormPickers.formatTime(minute)) { }
            }
            // Exams: the end time sits right under the start time.
            endRow = sheet.row {
                sheet.add(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
                endPicker = sheet.picker(ctx.getString(R.string.todo_exam_end), R.drawable.ic_form_schedule,
                    FormPickers.formatTime(endMinute)) { }
            }
        }
        val noDueSwitch = sheet.switch(ctx.getString(R.string.todo_bucket_no_date), noDue)

        fun refreshDue() {
            whenHeading.text = ctx.getString(if (exam) R.string.todo_form_when_exam else R.string.todo_form_due)
            datePicker.value = FormPickers.formatDate(date)
            timePicker.value = FormPickers.formatTime(minute)
            timePicker.layout.hint = ctx.getString(if (exam) R.string.todo_exam_start else R.string.todo_form_time)
            endPicker.value = FormPickers.formatTime(endMinute)
            endRow.visibility = if (exam) View.VISIBLE else View.GONE
            noDueSwitch.visibility = if (exam) View.GONE else View.VISIBLE
            dueFields.visibility = if (!exam && noDue) View.GONE else View.VISIBLE
            val meeting = meetings.getOrNull(meetingIndex)
            val showPresets = meeting != null && (exam || !noDue)
            meetingRow.visibility = if (showPresets) View.VISIBLE else View.GONE
            presetChips.group.visibility = if (showPresets) View.VISIBLE else View.GONE
            otherMeeting.visibility = if (meetings.size > 1) View.VISIBLE else View.GONE
            if (meeting != null) meetingLine.text = ctx.getString(R.string.todo_form_for_class,
                FormPickers.formatShortDate(meeting.date) + ", " + meeting.start.format(TIME_FORMAT) +
                    "–" + meeting.end.format(TIME_FORMAT))
        }

        // Presets relative to the selected class meeting: chips for tasks, "during class" for exams.
        fun applyPreset(index: Int) {
            val meeting = meetings.getOrNull(meetingIndex) ?: return
            if (exam) {
                date = meeting.date; minute = meeting.startMinute; endMinute = meeting.endMinute
            } else {
                val anchor = DueAnchor.values()[index]
                val due = anchor.resolve(meeting)
                date = due.toLocalDate(); minute = due.hour * 60 + due.minute
                lastAnchor = anchor
            }
            refreshDue()
        }

        fun rebuildChips(applyDefault: Boolean) {
            val labels = if (exam) listOf(ctx.getString(R.string.todo_form_during_class))
                else DueAnchor.values().map { ctx.getString(anchorShortLabel(it)) }
            val default = if (exam) 0 else DueAnchor.values().indexOf(lastAnchor)
            presetChips.setItems(labels, if (applyDefault && meetings.isNotEmpty()) default else null) { applyPreset(it) }
            if (applyDefault && meetings.isNotEmpty()) applyPreset(default)
        }

        fun loadMeetings(applyDefault: Boolean) {
            meetings = selectedCourseId()?.let { ClassMeetings.upcoming(data, it, LocalDateTime.now()) }.orEmpty()
            meetingIndex = 0
            rebuildChips(applyDefault)
            refreshDue()
        }

        otherMeeting.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.todo_pick_meeting)
                .setItems(meetings.map {
                    it.start.format(PRESET_FORMAT) + "–" + it.end.format(TIME_FORMAT)
                }.toTypedArray()) { _, which ->
                    meetingIndex = which
                    rebuildChips(applyDefault = true)
                    refreshDue()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        fun pickedByHand() { presetChips.clear(); refreshDue() }
        datePicker.setOnClick {
            FormPickers.pickDate(fm, ctx.getString(R.string.todo_form_date), date) { date = it; pickedByHand() }
        }
        timePicker.setOnClick {
            FormPickers.pickTime(fm, ctx.getString(R.string.todo_form_time), minute) { picked ->
                val duration = endMinute - minute
                minute = picked
                if (exam && duration > 0) endMinute = (picked + duration).coerceAtMost(END_OF_DAY_MINUTE)
                endPicker.setError(null)
                pickedByHand()
            }
        }
        endPicker.setOnClick {
            FormPickers.pickTime(fm, ctx.getString(R.string.todo_exam_end), endMinute) {
                endMinute = it; endPicker.setError(null); pickedByHand()
            }
        }
        noDueSwitch.setOnCheckedChangeListener { _, checked -> noDue = checked; refreshDue() }

        // ---- More options ----
        val hasDetails = existing != null && (existing.note.isNotBlank() || existing.priority != TaskPriority.NONE ||
            existing.repeatRule != null || existingChecklist.isNotEmpty())
        lateinit var taskOnly: View
        sheet.collapsible(ctx.getString(R.string.todo_form_more), expanded = hasDetails) {
            sheet.section(ctx.getString(R.string.todo_form_details))
            note = sheet.text(ctx.getString(R.string.todo_task_note), existing?.note.orEmpty(), multiline = true)
            taskOnly = sheet.group {
                sheet.note(ctx.getString(R.string.todo_form_priority))
                priority = sheet.segments(PRIORITY_SHORT_LABELS.map { ctx.getString(it) },
                    setOf(existing?.priority?.coerceIn(0, PRIORITY_SHORT_LABELS.size - 1) ?: 0))
                repeat = sheet.dropdown(ctx.getString(R.string.todo_form_repeat),
                    repeatOptions.map { repeatLabel(ctx, it) }, repeatIndex) { index ->
                    repeat.layout.helperText =
                        if (repeatOptions[index] != null) ctx.getString(R.string.todo_form_repeat_helper) else null
                }
                if (repeatOptions[repeatIndex] != null) {
                    repeat.layout.helperText = ctx.getString(R.string.todo_form_repeat_helper)
                }
            }
            sheet.section(ctx.getString(R.string.todo_checklist))
            checklist = sheet.group { }
            existingChecklist.forEach { addChecklistRow(sheet, checklist, it.text, it.done) }
            sheet.textButton(ctx.getString(R.string.todo_checklist_add), R.drawable.ic_add) {
                addChecklistRow(sheet, checklist, "", false).requestFocus()
            }
        }

        fun refreshKind() {
            taskOnly.visibility = if (exam) View.GONE else View.VISIBLE
            title.hint = null
            (title.parent.parent as? com.google.android.material.textfield.TextInputLayout)?.placeholderText =
                ctx.getString(if (exam) R.string.todo_form_exam_example else R.string.todo_form_task_example)
        }

        // A new task gets the default preset; an edited task keeps its due time.
        loadMeetings(applyDefault = existing == null)
        refreshKind()
        kind.group.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            exam = kind.first == 1
            if (exam && minute == END_OF_DAY_MINUTE) {
                minute = DEFAULT_EXAM_START
                endMinute = DEFAULT_EXAM_START + DEFAULT_EXAM_MINUTES
            }
            rebuildChips(applyDefault = meetings.isNotEmpty())
            refreshKind()
            refreshDue()
        }
        // Course changes re-target the presets.
        onCourseChange = { loadMeetings(applyDefault = true) }
        sheet.show()
    }

    /** Checklist row: done tick, text and a remove button. */
    private fun addChecklistRow(sheet: FormSheet, container: LinearLayout, text: String, done: Boolean): EditText {
        val ctx = sheet.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val check = CheckBox(ctx).apply { isChecked = done }
        val edit = EditText(ctx).apply {
            setText(text)
            hint = ctx.getString(R.string.todo_checklist_item_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(check)
        row.addView(edit)
        row.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = ctx.getString(R.string.todo_checklist_remove)
            background = null
            setOnClickListener { container.removeView(row) }
        })
        container.addView(row)
        return edit
    }

    private fun checklistItems(container: LinearLayout): List<ChecklistItem> =
        (0 until container.childCount).mapNotNull { index ->
            val row = container.getChildAt(index) as LinearLayout
            val done = (row.getChildAt(0) as CheckBox).isChecked
            val text = (row.getChildAt(1) as EditText).text.toString().trim()
            if (text.isEmpty()) null else ChecklistItem(taskId = 0, text = text, done = done)
        }

    private class DuePreset(
        val label: String,
        val anchor: DueAnchor?,
        val pickOther: Boolean = false,
        val duringClass: Boolean = false,
    )

    private const val DEFAULT_EXAM_START = 9 * 60
    private const val DEFAULT_EXAM_MINUTES = 90
    private val PRIORITY_SHORT_LABELS = listOf(R.string.todo_form_priority_none, R.string.todo_form_priority_low,
        R.string.todo_form_priority_medium, R.string.todo_form_priority_high)
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

    private const val END_OF_DAY_MINUTE = 23 * 60 + 59
    private const val PREFS_NAME = "etar_timetable_prefs"
    private const val KEY_LAST_COURSE = "last_task_course"
    private val PRESET_FORMAT = DateTimeFormatter.ofPattern("EEE d/M HH:mm", Locale.getDefault())
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    private fun anchorShortLabel(anchor: DueAnchor) = when (anchor) {
        DueAnchor.BEFORE_CLASS -> R.string.todo_form_anchor_before
        DueAnchor.START_OF_CLASS -> R.string.todo_form_anchor_start
        DueAnchor.END_OF_CLASS -> R.string.todo_form_anchor_end
        DueAnchor.DAY_BEFORE -> R.string.todo_form_anchor_day_before
    }

    /** Like Power Planner, new tasks start with the course used last time. */
    private fun lastCourseId(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_COURSE, -1L).takeIf { it != -1L }

    private fun rememberCourse(context: Context, courseId: Long?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_COURSE, courseId ?: -1L).apply()
    }

}
