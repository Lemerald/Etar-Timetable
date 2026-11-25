package com.android.calendar.timetable.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.calendar.CalendarController
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TaskPriority
import com.android.calendar.timetable.data.TodoBucket
import com.android.calendar.timetable.data.TodoBuckets
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.TimetableRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ws.xsoh.etar.R

/** Simple task list, grouped by due-date buckets, with course tagging. */
class TodoFragment : Fragment(R.layout.fragment_todos), CalendarController.EventHandler {

    private val repository by lazy { TimetableRepository.get(requireContext()) }
    private var data = TimetableData()
    private var showCompleted = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<LinearLayout>(R.id.todo_list)
        view.findViewById<View>(R.id.todo_new_task).setOnClickListener {
            TaskDialog.show(requireActivity(), repository, data)
        }
        val toggle = view.findViewById<CheckBox>(R.id.todo_show_completed)
        toggle.isChecked = showCompleted
        toggle.setOnCheckedChangeListener { _, checked ->
            showCompleted = checked
            render(list)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.data.collect { newData ->
                    data = newData
                    render(list)
                }
            }
        }
    }

    override fun getSupportedEventTypes(): Long = 0L
    override fun handleEvent(event: CalendarController.EventInfo?) = Unit
    override fun eventsChanged() = Unit

    private fun render(list: LinearLayout) {
        list.removeAllViews()
        val now = LocalDateTime.now()
        val groups = TodoBuckets.group(data.tasks, now, showCompleted)
        for ((bucket, tasks) in groups) {
            list.addView(TextView(requireContext()).apply {
                text = getString(bucketTitle(bucket))
                textSize = 16f
                setTextColor(if (bucket == TodoBucket.OVERDUE) OVERDUE_COLOR
                    else resources.getColor(R.color.colorPrimary, null))
                setPadding(0, dp(12), 0, dp(4))
            })
            val nowMillis = System.currentTimeMillis()
            for (task in tasks) list.addView(taskRow(task, TodoBuckets.isDone(task, nowMillis)))
        }
    }

    private fun bucketTitle(bucket: TodoBucket) = when (bucket) {
        TodoBucket.OVERDUE -> R.string.todo_bucket_overdue
        TodoBucket.TODAY -> R.string.todo_bucket_today
        TodoBucket.TOMORROW -> R.string.todo_bucket_tomorrow
        TodoBucket.THIS_WEEK -> R.string.todo_bucket_this_week
        TodoBucket.LATER -> R.string.todo_bucket_later
        TodoBucket.NO_DATE -> R.string.todo_bucket_no_date
    }

    /** Title line with priority, course and exam tags; second line with due time and checklist progress. */
    private fun label(task: Task): CharSequence {
        val course = task.courseId?.let { data.courseById(it) }
        val text = SpannableStringBuilder()
        if (task.priority != TaskPriority.NONE) {
            text.append("!".repeat(task.priority) + " ",
                ForegroundColorSpan(PRIORITY_COLORS[task.priority]), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(task.title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (task.isExam) {
            text.append("  ").append(getString(R.string.todo_exam_tag),
                ForegroundColorSpan(OVERDUE_COLOR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val details = buildList {
            if (course != null) add(course)
            task.dueAtMillis?.let { add(formatDue(task, it)) }
            val checklist = data.checklistFor(task.id)
            if (checklist.isNotEmpty()) add("☑ ${checklist.count { it.done }}/${checklist.size}")
            if (task.repeatRule != null) add("↻")
        }
        if (details.isNotEmpty()) text.append('\n')
        details.forEachIndexed { index, detail ->
            if (index > 0) text.append("  ·  ")
            if (detail is Course) {
                text.append("● ", ForegroundColorSpan(detail.color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append(detail.name)
            } else {
                text.append(detail.toString())
            }
        }
        return text
    }

    private fun formatDue(task: Task, due: Long): String {
        val start = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault())
        val base = start.toLocalDate().format(DATE_FORMAT) + " " + start.toLocalTime().format(TIME_FORMAT)
        val end = task.endAtMillis?.takeIf { task.isExam }
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
        return if (end != null) base + "–" + end.format(TIME_FORMAT) else base
    }

    private fun taskRow(task: Task, done: Boolean): View {
        val column = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (task.isExam) {
            // Exams are appointments: they are done once over, not checked off.
            row.addView(TextView(requireContext()).apply {
                text = label(task)
                layoutParams = weight
                setPadding(dp(8), dp(4), 0, dp(4))
                alpha = if (done) 0.5f else 1f
            })
        } else {
            row.addView(CheckBox(requireContext()).apply {
                text = label(task)
                isChecked = task.completed
                layoutParams = weight
                setOnCheckedChangeListener { _, checked ->
                    launch { repository.setTaskCompleted(task, checked) }
                }
            })
        }
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(R.drawable.outline_edit_event)
            contentDescription = getString(R.string.todo_edit_task)
            setOnClickListener {
                TaskDialog.show(requireActivity(), repository, data, existing = task)
            }
        })
        row.addView(ImageButton(requireContext()).apply {
            setImageResource(R.drawable.outline_delete_event)
            contentDescription = getString(R.string.todo_delete_task)
            setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.todo_delete_task)
                    .setMessage(task.title)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.yes) { _, _ ->
                        launch { repository.deleteTaskSynced(task) }
                    }
                    .show()
            }
        })
        column.addView(row)
        if (!done) {
            for (item in data.checklistFor(task.id)) {
                column.addView(CheckBox(requireContext()).apply {
                    text = item.text
                    isChecked = item.done
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(40) }
                    setOnCheckedChangeListener { _, checked ->
                        launch { repository.setChecklistItemDone(item, checked) }
                    }
                })
            }
        }
        return column
    }

    private fun launch(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/u", Locale.getDefault())
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        const val OVERDUE_COLOR = 0xFFD1270C.toInt()
        /** Indexed by priority: none, low, medium, high. */
        val PRIORITY_COLORS = intArrayOf(0, 0xFF1454B9.toInt(), 0xFFE69500.toInt(), 0xFFD1270C.toInt())
    }
}
