package com.android.calendar.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.android.calendar.timetable.data.Course
import com.android.calendar.timetable.data.OneTimeSlot
import com.android.calendar.timetable.data.RegularSlot
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TimetableData
import com.android.calendar.timetable.data.minutesToTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A reference to the slot drawn at a given place, used for click handling. */
sealed interface SlotRef {
    data class Regular(val slot: RegularSlot) : SlotRef
    data class OneTime(val slot: OneTimeSlot) : SlotRef
}

/**
 * Static weekly grid of a term's slots. This is intentionally data-based:
 * holidays and generated events do not affect what is drawn here.
 */
class TimetableGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var data: TimetableData = TimetableData()
    var termId: Long = -1L
    var weekStart: LocalDate = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
    var onSlotClick: ((SlotRef) -> Unit)? = null

    private val gutter = dp(46)
    private val headerHeight = dp(46)
    private val columnWidth = dp(128)
    private val hourHeight = dp(54)
    private val firstHour = 7
    private val lastHour = 22
    private val blockPadding = dp(3)

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val freePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clickableSlots = mutableListOf<Pair<RectF, SlotRef>>()

    // Detect taps without consuming drag events, so the surrounding ScrollViews can scroll.
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val hit = clickableSlots.firstOrNull { (rect, _) -> rect.contains(event.x, event.y) }?.second
            if (hit != null) onSlotClick?.invoke(hit)
            return hit != null
        }
    })

    init {
        setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event)
            false
        }
        gridPaint.color = Color.parseColor("#DDDDDD")
        gridPaint.strokeWidth = 1f
        hourPaint.color = Color.GRAY
        hourPaint.textSize = sp(11)
        hourPaint.textAlign = Paint.Align.RIGHT
        headerTextPaint.textSize = sp(12)
        headerTextPaint.textAlign = Paint.Align.CENTER
        timeTextPaint.textSize = sp(11)
        timeTextPaint.textAlign = Paint.Align.RIGHT
        titlePaint.textSize = sp(12)
        titlePaint.isFakeBoldText = true
        detailPaint.textSize = sp(11)
        badgePaint.color = Color.parseColor("#E53935")
        badgeTextPaint.color = Color.WHITE
        badgeTextPaint.textSize = sp(10)
        badgeTextPaint.textAlign = Paint.Align.CENTER
        slotPaint.style = Paint.Style.FILL
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = dp(2).toFloat()
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(4).toFloat()), 0f)
        dashPaint.color = Color.WHITE
        headerPaint.color = Color.parseColor("#F5F5F5")
        todayPaint.color = Color.parseColor("#D7F0EC")
        freePaint.color = Color.parseColor("#E0E0E0")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = gutter + 7 * columnWidth
        val height = headerHeight + (lastHour - firstHour) * hourHeight
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        clickableSlots.clear()
        val today = LocalDate.now()
        val freeDays = data.freeDays.filter { it.termId == termId }.map { it.dateEpochDay }.toSet()
        val courses = data.courses.associateBy { it.id }
        val tasksDue = data.tasks.filter { !it.completed && it.dueAtMillis != null }

        // Day headers.
        for (dayIndex in 0 until 7) {
            val date = weekStart.plusDays(dayIndex.toLong())
            val x = gutter + dayIndex * columnWidth
            canvas.drawRect(x.toFloat(), 0f, (x + columnWidth).toFloat(), headerHeight.toFloat(),
                when {
                    date == today -> todayPaint
                    date.toEpochDay() in freeDays -> freePaint
                    else -> headerPaint
                })
            val name = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            headerTextPaint.color = Color.parseColor("#333333")
            canvas.drawText(name, x + columnWidth / 2f, headerHeight * 0.45f, headerTextPaint)
            canvas.drawText("${date.dayOfMonth}/${date.monthValue}", x + columnWidth / 2f,
                headerHeight * 0.85f, headerTextPaint)

            val dueCount = tasksDue.count { taskDate(it) == date }
            if (dueCount > 0) {
                val cx = x + columnWidth - dp(14)
                val cy = headerHeight - dp(12)
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), dp(9).toFloat(), badgePaint)
                badgeTextPaint.color = Color.WHITE
                canvas.drawText(dueCount.toString(), cx.toFloat(), (cy + dp(3)).toFloat(), badgeTextPaint)
            }
        }

        // Hour grid and labels.
        for (hour in firstHour..lastHour) {
            val y = headerHeight + (hour - firstHour) * hourHeight
            canvas.drawLine(gutter.toFloat(), y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
            if (hour < lastHour) {
                timeTextPaint.color = Color.GRAY
                canvas.drawText("%02d:00".format(hour), gutter - dp(6).toFloat(), y + sp(4), timeTextPaint)
            }
        }
        for (dayIndex in 0..7) {
            val x = gutter + dayIndex * columnWidth
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
        }
        canvas.drawLine(gutter.toFloat(), headerHeight.toFloat(), width.toFloat(),
            headerHeight.toFloat(), gridPaint)

        // Slots: regular slots always, one-off slots only in the displayed week.
        val regular = data.regularSlots.filter { it.termId == termId }
        val oneTime = data.oneTimeSlots.filter {
            it.termId == termId && it.date >= weekStart && it.date <= weekStart.plusDays(6)
        }
        for (dayIndex in 0 until 7) {
            val date = weekStart.plusDays(dayIndex.toLong())
            // Courses limited to part of the term only show while they run.
            for (slot in regular.filter {
                it.weekday == date.dayOfWeek.value && courses[it.courseId]?.isActiveOn(date.toEpochDay()) != false
            }) {
                drawSlot(canvas, courses[slot.courseId], dayIndex, slot.startMinute, slot.endMinute,
                    oneOff = false, SlotRef.Regular(slot))
            }
            for (slot in oneTime.filter { it.date == date }) {
                drawSlot(canvas, courses[slot.courseId], dayIndex, slot.startMinute, slot.endMinute,
                    oneOff = true, SlotRef.OneTime(slot))
            }
        }

        // Due-time markers: a thin coloured line at the task's time.
        for (task in tasksDue) {
            val due = taskDate(task) ?: continue
            if (due < weekStart || due > weekStart.plusDays(6)) continue
            val dayIndex = java.time.temporal.ChronoUnit.DAYS.between(weekStart, due).toInt()
            val instant = Instant.ofEpochMilli(task.dueAtMillis!!)
            val time = instant.atZone(ZoneId.systemDefault()).toLocalTime()
            val minute = time.hour * 60 + time.minute
            if (minute < firstHour * 60 || minute > lastHour * 60) continue
            val y = headerHeight + ((minute - firstHour * 60) / 60f * hourHeight).roundToInt()
            val x = gutter + dayIndex * columnWidth
            val course = task.courseId?.let { courses[it] }
            detailPaint.color = course?.color ?: Color.parseColor("#E53935")
            canvas.drawLine(x.toFloat(), y.toFloat(), (x + columnWidth).toFloat(),
                y + dp(2).toFloat(), detailPaint)
        }
    }

    private fun drawSlot(
        canvas: Canvas, course: Course?, dayIndex: Int, startMinute: Int, endMinute: Int,
        oneOff: Boolean, ref: SlotRef,
    ) {
        val color = course?.color ?: Color.parseColor("#777777")
        val x = gutter + dayIndex * columnWidth + blockPadding
        val y = headerHeight + ((startMinute - firstHour * 60) / 60f * hourHeight).roundToInt()
        val widthPx = columnWidth - 2 * blockPadding
        val heightPx = ((endMinute - startMinute) / 60f * hourHeight).roundToInt().coerceAtLeast(dp(20))
        val rect = RectF(x.toFloat(), y.toFloat(), (x + widthPx).toFloat(), (y + heightPx).toFloat())

        val dark = isDark(color)
        slotPaint.color = color
        slotPaint.alpha = 215
        canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), slotPaint)
        slotPaint.alpha = 255
        if (oneOff) {
            dashPaint.color = if (dark) Color.WHITE else Color.BLACK
            canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), dashPaint)
        }
        val textColor = if (dark) Color.WHITE else Color.BLACK
        titlePaint.color = textColor
        detailPaint.color = textColor
        val title = TextUtils.ellipsize(course?.name ?: "", titlePaint,
            (widthPx - dp(12)).toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(title, (x + dp(6)).toFloat(), (y + dp(14)).toFloat(), titlePaint)
        course?.room?.takeIf { it.isNotBlank() }?.let { roomText ->
            val room = TextUtils.ellipsize(roomText, detailPaint,
                (widthPx - dp(12)).toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(room, (x + dp(6)).toFloat(), (y + dp(28)).toFloat(), detailPaint)
        }
        if (heightPx > dp(48)) {
            canvas.drawText("${minutesToTime(startMinute)}–${minutesToTime(endMinute)}",
                (x + dp(6)).toFloat(), (y + dp(42)).toFloat(), detailPaint)
        }
        clickableSlots += rect to ref
    }

    private fun taskDate(task: Task): LocalDate? =
        task.dueAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299 * r + 0.587 * g + 0.114 * b < 0.6
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity
}
