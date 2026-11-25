package com.android.calendar.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TodoBucketsTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.parse("2026-11-25T12:00") // Wednesday

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun task(title: String, due: String?, completed: Boolean = false, priority: Int = 0) =
        Task(title = title, dueAtMillis = due?.let(::at), completed = completed, priority = priority)

    private fun bucket(task: Task) = TodoBuckets.bucketOf(task, now, zone)

    @Test
    fun buckets() {
        assertEquals(TodoBucket.OVERDUE, bucket(task("a", "2026-11-25T09:00")))
        assertEquals(TodoBucket.OVERDUE, bucket(task("b", "2026-11-20T09:00")))
        assertEquals(TodoBucket.TODAY, bucket(task("c", "2026-11-25T18:00")))
        assertEquals(TodoBucket.TOMORROW, bucket(task("d", "2026-11-26T08:00")))
        assertEquals(TodoBucket.THIS_WEEK, bucket(task("e", "2026-11-29T23:59")))
        assertEquals(TodoBucket.LATER, bucket(task("f", "2026-11-30T00:00")))
        assertEquals(TodoBucket.NO_DATE, bucket(task("g", null)))
    }

    @Test
    fun completedPastTaskStaysInItsDateGroup() {
        assertEquals(TodoBucket.TODAY, bucket(task("a", "2026-11-25T09:00", completed = true)))
    }

    @Test
    fun examsAreDoneWhenOverAndNeverOverdue() {
        val running = Task(title = "x", kind = TaskKind.EXAM, dueAtMillis = at("2026-11-25T11:00"),
            endAtMillis = at("2026-11-25T13:00"))
        val over = running.copy(endAtMillis = at("2026-11-25T11:30"))
        assertEquals(TodoBucket.TODAY, bucket(running))
        val groups = TodoBuckets.group(listOf(running, over), now, showDone = false, zone)
        assertEquals(listOf(running), groups[TodoBucket.TODAY])
    }

    @Test
    fun groupOrderAndSorting() {
        val tasks = listOf(
            task("later", "2026-12-10T10:00"),
            task("low", "2026-11-25T18:00", priority = TaskPriority.LOW),
            task("high", "2026-11-25T18:00", priority = TaskPriority.HIGH),
            task("early", "2026-11-25T14:00"),
            task("done", "2026-11-25T13:00", completed = true),
            task("late", "2026-11-24T10:00"),
        )
        val hidden = TodoBuckets.group(tasks, now, showDone = false, zone)
        assertEquals(listOf(TodoBucket.OVERDUE, TodoBucket.TODAY, TodoBucket.LATER), hidden.keys.toList())
        assertEquals(listOf("early", "high", "low"), hidden[TodoBucket.TODAY]!!.map { it.title })

        val shown = TodoBuckets.group(tasks, now, showDone = true, zone)
        assertEquals(listOf("early", "high", "low", "done"), shown[TodoBucket.TODAY]!!.map { it.title })
    }
}
