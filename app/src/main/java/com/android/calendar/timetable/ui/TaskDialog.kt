package com.android.calendar.timetable.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
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
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TimetableRepository
import com.android.calendar.timetable.data.minutesToTime
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
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
            show(activity, repository, repository.coursesOnce())
        }
    }

    fun show(
        activity: FragmentActivity,
        repository: TimetableRepository,
        courses: List<Course>,
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
            val courseIndex = courses.indexOfFirst { it.id == (existing?.courseId ?: defaultCourseId) }
            setSelection(if (courseIndex >= 0) courseIndex + 1 else 0)
        }

        var date: LocalDate = existing?.dueAtMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: defaultDue ?: LocalDate.now()
        var minute: Int = existing?.dueAtMillis?.let {
            val time = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
            time.hour * 60 + time.minute
        } ?: 9 * 60

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
        dateButton.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.todo_due_date)
                .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                date = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
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
            picker.addOnPositiveButtonClickListener { minute = picker.hour * 60 + picker.minute; refreshDue() }
            picker.show(activity.supportFragmentManager, "task_time")
        }

        layout.addView(title)
        layout.addView(note)
        layout.addView(courseSpinner)
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
            val courseId = if (courseSpinner.selectedItemPosition == 0) null
            else courses[courseSpinner.selectedItemPosition - 1].id
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
