package com.android.calendar.timetable.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.calendar.CalendarController
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.TimetableRepository
import com.android.calendar.timetable.ui.manage.TimetableEditorActivity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ws.xsoh.etar.R

/** Top-level timetable view: a static weekly grid plus assignments due that week. */
class TimetableFragment : Fragment(R.layout.fragment_timetable), CalendarController.EventHandler {

    private val repository by lazy { TimetableRepository.get(requireContext()) }
    private var data = TimetableData()
    private var weekStart: LocalDate = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
    private var activeTermId = -1L
    private var displayedTermIds = emptyList<Long>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val grid = view.findViewById<TimetableGridView>(R.id.timetable_grid)
        val spinner = view.findViewById<android.widget.Spinner>(R.id.timetable_term_spinner)
        val assignments = view.findViewById<android.widget.TextView>(R.id.timetable_assignments)

        grid.onSlotClick = {
            val intent = Intent(requireContext(), TimetableEditorActivity::class.java)
            intent.putExtra(TimetableEditorActivity.EXTRA_TERM_ID, activeTermId)
            startActivity(intent)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val terms = data.terms
                if (position in terms.indices) {
                    activeTermId = terms[position].id
                    update(grid, assignments)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        view.findViewById<View>(R.id.timetable_prev_week).setOnClickListener {
            weekStart = weekStart.minusWeeks(1)
            update(grid, assignments)
        }
        view.findViewById<View>(R.id.timetable_next_week).setOnClickListener {
            weekStart = weekStart.plusWeeks(1)
            update(grid, assignments)
        }
        view.findViewById<View>(R.id.timetable_manage).setOnClickListener {
            startActivity(Intent(requireContext(), TimetableEditorActivity::class.java))
        }
        view.findViewById<View>(R.id.timetable_new_task).setOnClickListener {
            TaskDialog.show(
                activity = requireActivity(),
                repository = repository,
                courses = data.coursesForTerm(activeTermId),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.data.collect { newData ->
                    data = newData
                    val ids = newData.terms.map { it.id }
                    if (ids != displayedTermIds) {
                        displayedTermIds = ids
                        val names = if (ids.isEmpty()) {
                            listOf(getString(R.string.timetable_no_terms))
                        } else {
                            newData.terms.map { it.name }
                        }
                        val adapter = ArrayAdapter(requireContext(),
                            android.R.layout.simple_spinner_item, names)
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        spinner.adapter = adapter
                        if (ids.isNotEmpty() && activeTermId !in ids) activeTermId = ids.first()
                    }
                    if (ids.isEmpty()) activeTermId = -1L
                    update(grid, assignments)
                }
            }
        }
    }

    // This view is not part of the CalendarController event flow; Etar registers every
    // main-pane fragment as an event handler, so provide a no-op implementation.
    override fun getSupportedEventTypes(): Long = 0L

    override fun handleEvent(event: CalendarController.EventInfo?) = Unit

    override fun eventsChanged() = Unit

    private fun update(grid: TimetableGridView, assignments: android.widget.TextView) {
        grid.termId = activeTermId
        grid.data = data
        grid.weekStart = weekStart
        grid.invalidate()

        val weekEnd = weekStart.plusDays(6)
        val due = data.tasks.filter { task ->
            !task.completed && task.dueAtMillis != null && run {
                val date = Instant.ofEpochMilli(task.dueAtMillis!!)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                date in weekStart..weekEnd
            }
        }.sortedBy { it.dueAtMillis }

        if (due.isEmpty()) {
            assignments.text = getString(R.string.timetable_no_assignments)
            return
        }
        val builder = SpannableStringBuilder()
        for (task in due) {
            val start = builder.length
            val courseName = task.courseId?.let { data.courseById(it)?.name }
            if (courseName != null) {
                builder.append(courseName)
                builder.setSpan(ForegroundColorSpan(data.courseById(task.courseId!!)?.color ?: Color.GRAY),
                    start, builder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(" — ")
            }
            builder.append(task.title)
            val millis = task.dueAtMillis!!
            val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            builder.append(" (")
                .append(date.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d/M")))
                .append(", ")
                .append(date.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                .append(")\n")
        }
        assignments.text = builder
    }
}
