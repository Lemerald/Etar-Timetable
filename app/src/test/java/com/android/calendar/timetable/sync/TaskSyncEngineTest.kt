package com.android.calendar.timetable.sync

import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TaskKind
import com.android.calendar.timetable.data.TaskPriority
import com.android.calendar.timetable.sync.TaskSyncEngine.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TaskSyncEngineTest {

    private val lastSync = 1_000L

    private fun local(id: Long, remoteId: Long?, modified: Long, remoteModified: Long? = 500, title: String = "t$id") =
        Task(id = id, title = title, remoteId = remoteId, modifiedAtMillis = modified,
            remoteModifiedAtMillis = remoteModified, createdAtMillis = 0)

    private fun remote(id: Long, modified: Long, title: String = "r$id") =
        RemoteTask(id, title, "", null, null, false, null, 0, modified)

    @Test
    fun newLocalTasksArePushed() {
        val task = local(1, null, 900, null)
        assertEquals(listOf(Action.PushCreate(task)), TaskSyncEngine.plan(listOf(task), emptyList(), lastSync))
    }

    @Test
    fun newRemoteTasksArePulled() {
        val row = remote(7, 2_000)
        assertEquals(listOf(Action.PullCreate(row)), TaskSyncEngine.plan(emptyList(), listOf(row), lastSync))
        assertEquals(emptyList<Action>(),
            TaskSyncEngine.plan(emptyList(), listOf(row.copy(cancelled = true)), lastSync))
    }

    @Test
    fun cleanTasksAreLeftAlone() {
        val task = local(1, 7, 900, 500, title = "r7")
        assertEquals(emptyList<Action>(), TaskSyncEngine.plan(listOf(task), listOf(remote(7, 500)), lastSync))
    }

    @Test
    fun oneSidedChanges() {
        val localChange = local(1, 7, 1_500, 500)
        assertEquals(listOf(Action.PushUpdate(localChange, 7)),
            TaskSyncEngine.plan(listOf(localChange), listOf(remote(7, 500)), lastSync))

        val clean = local(1, 7, 900, 500)
        val changedRow = remote(7, 2_000, title = "renamed")
        assertEquals(listOf(Action.PullUpdate(clean, changedRow)),
            TaskSyncEngine.plan(listOf(clean), listOf(changedRow), lastSync))
    }

    @Test
    fun remoteTouchWithoutVisibleChangeOnlyRelinks() {
        val clean = local(1, 7, 900, 500, title = "same")
        val row = remote(7, 2_000, title = "same")
        assertEquals(listOf(Action.Relink(clean, row)), TaskSyncEngine.plan(listOf(clean), listOf(row), lastSync))
    }

    @Test
    fun conflictNewerWins() {
        val task = local(1, 7, 3_000, 500)
        assertEquals(listOf(Action.PushUpdate(task, 7)),
            TaskSyncEngine.plan(listOf(task), listOf(remote(7, 2_000)), lastSync))
        val row = remote(7, 4_000)
        assertEquals(listOf(Action.PullUpdate(task, row)), TaskSyncEngine.plan(listOf(task), listOf(row), lastSync))
    }

    @Test
    fun remoteDeletion() {
        val clean = local(1, 7, 900)
        assertEquals(listOf(Action.DeleteLocal(clean)), TaskSyncEngine.plan(listOf(clean), emptyList(), lastSync))
        val edited = local(1, 7, 1_500)
        assertEquals(listOf(Action.PushCreate(edited)), TaskSyncEngine.plan(listOf(edited), emptyList(), lastSync))
    }

    @Test
    fun applyRemoteKeepsLocalOnlyFields() {
        val task = Task(id = 1, title = "a", courseId = 4, repeatRule = "FREQ=WEEKLY;INTERVAL=1",
            dueAtMillis = 10, createdAtMillis = 0)
        val row = RemoteTask(7, "b", "note", null, 20, true, 30, 1, 99)
        val pulled = TaskSyncEngine.applyRemote(task, row)
        assertEquals("b", pulled.title)
        assertEquals("note", pulled.note)
        assertEquals(20L, pulled.dueAtMillis)
        assertEquals(30L, pulled.completedAtMillis)
        assertEquals(TaskPriority.HIGH, pulled.priority)
        assertEquals(4L, pulled.courseId)
        assertEquals("FREQ=WEEKLY;INTERVAL=1", pulled.repeatRule)
    }

    @Test
    fun examsMapStartAndDue() {
        val exam = Task(id = 1, title = "x", kind = TaskKind.EXAM, dueAtMillis = 10, endAtMillis = 20, createdAtMillis = 0)
        val pulled = TaskSyncEngine.applyRemote(exam, RemoteTask(7, "x", "", 100, 200, false, null, 0, 1))
        assertEquals(100L, pulled.dueAtMillis)
        assertEquals(200L, pulled.endAtMillis)
        assertFalse(TaskSyncEngine.differs(pulled, RemoteTask(7, "x", "", 100, 200, false, null, 0, 1)))
    }

    @Test
    fun priorityMapping() {
        for (p in listOf(TaskPriority.NONE, TaskPriority.LOW, TaskPriority.MEDIUM, TaskPriority.HIGH)) {
            assertEquals(p, TaskSyncEngine.fromRfcPriority(TaskSyncEngine.toRfcPriority(p)))
        }
        assertEquals(TaskPriority.HIGH, TaskSyncEngine.fromRfcPriority(3))
        assertEquals(TaskPriority.LOW, TaskSyncEngine.fromRfcPriority(7))
    }
}
