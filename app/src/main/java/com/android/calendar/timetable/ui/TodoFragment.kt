package com.android.calendar.timetable.ui

import android.os.Bundle
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
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.TimetableRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
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
        val today = LocalDate.now()
        val endOfWeek = today.with(java.time.DayOfWeek.SUNDAY)
        val buckets = linkedMapOf(
            R.string.todo_bucket_today to mutableListOf<Task>(),
            R.string.todo_bucket_tomorrow to mutableListOf(),
            R.string.todo_bucket_this_week to mutableListOf(),
            R.string.todo_bucket_later to mutableListOf(),
            R.string.todo_bucket_no_date to mutableListOf(),
        )
        for (task in data.tasks.filter { showCompleted || !it.completed }) {
            val bucket = when {
                task.dueAtMillis == null -> R.string.todo_bucket_no_date
                else -> {
                    val date = Instant.ofEpochMilli(task.dueAtMillis!!)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    when {
                        !date.isAfter(today) -> R.string.todo_bucket_today
                        date == today.plusDays(1) -> R.string.todo_bucket_tomorrow
                        !date.isAfter(endOfWeek) -> R.string.todo_bucket_this_week
                        else -> R.string.todo_bucket_later
                    }
                }
            }
            buckets[bucket]!! += task
        }

        for ((titleRes, tasks) in buckets) {
            if (tasks.isEmpty()) continue
            list.addView(TextView(requireContext()).apply {
                text = getString(titleRes)
                textSize = 16f
                setTextColor(resources.getColor(R.color.colorPrimary, null))
                setPadding(0, dp(12), 0, dp(4))
            })
            for (task in tasks.sortedWith(
                compareBy<Task> { it.completed }.thenBy { it.dueAtMillis ?: Long.MAX_VALUE }
            )) {
                list.addView(taskRow(task))
            }
        }
    }

    private fun taskRow(task: Task): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val course = task.courseId?.let { data.courseById(it) }
        val label = buildString {
            append(task.title)
            if (course != null) append("  [").append(course.name).append(']')
            task.dueAtMillis?.let {
                val dateTime = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                append('\n').append(dateTime.toLocalDate().format(DATE_FORMAT))
                    .append(' ').append(dateTime.toLocalTime().format(TIME_FORMAT))
            }
        }
        row.addView(CheckBox(requireContext()).apply {
            text = label
            isChecked = task.completed
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, checked ->
                launch {
                    repository.updateTaskSynced(task.copy(
                        completed = checked,
                        completedAtMillis = if (checked) System.currentTimeMillis() else null,
                    ))
                }
            }
        })
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
        return row
    }

    private fun launch(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/u", Locale.getDefault())
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }
}
