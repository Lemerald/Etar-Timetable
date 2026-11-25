/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.sync

import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TaskPriority

/** A task row of the chosen OpenTasks list (only the fields the bridge syncs). */
data class RemoteTask(
    val id: Long,
    val title: String,
    val description: String,
    val start: Long?,
    val due: Long?,
    val completed: Boolean,
    val completedAt: Long?,
    /** RFC 5545 priority: 0 = undefined, 1 = highest … 9 = lowest. */
    val priority: Int,
    val lastModified: Long,
    val cancelled: Boolean = false,
)

/**
 * Decides how to reconcile local tasks (source of truth, may be offline) with one
 * OpenTasks list that DAVx5 syncs to CalDAV. Pure: the caller executes the actions.
 *
 * Change detection: a local task is dirty when it was modified after the last sync
 * run ([lastSyncMillis]); a remote task is dirty when its LAST_MODIFIED differs from
 * the value recorded at the last sync ([Task.remoteModifiedAtMillis]). When both
 * changed, the newer modification wins.
 */
object TaskSyncEngine {

    sealed class Action {
        /** Create the task remotely and link it. */
        data class PushCreate(val task: Task) : Action()
        data class PushUpdate(val task: Task, val remoteId: Long) : Action()
        /** Overwrite the local task with the remote fields. */
        data class PullUpdate(val task: Task, val remote: RemoteTask) : Action()
        /** A task created elsewhere (Tasks.org, another device). */
        data class PullCreate(val remote: RemoteTask) : Action()
        /** The remote task was deleted elsewhere and the local copy is unchanged. */
        data class DeleteLocal(val task: Task) : Action()
        /** Only refresh the recorded remote LAST_MODIFIED. */
        data class Relink(val task: Task, val remote: RemoteTask) : Action()
    }

    fun plan(local: List<Task>, remote: List<RemoteTask>, lastSyncMillis: Long): List<Action> {
        val remoteById = remote.associateBy { it.id }
        val linkedIds = local.mapNotNull { it.remoteId }.toSet()
        val actions = ArrayList<Action>()

        for (task in local) {
            val localDirty = task.modifiedAtMillis > lastSyncMillis
            val remoteId = task.remoteId
            if (remoteId == null) {
                actions += Action.PushCreate(task)
                continue
            }
            val row = remoteById[remoteId]
            if (row == null) {
                // Deleted elsewhere (or moved to another list): keep local edits, else follow the deletion.
                actions += if (localDirty) Action.PushCreate(task) else Action.DeleteLocal(task)
                continue
            }
            val remoteDirty = row.lastModified != task.remoteModifiedAtMillis
            actions += when {
                localDirty && remoteDirty ->
                    if (task.modifiedAtMillis >= row.lastModified) Action.PushUpdate(task, remoteId)
                    else Action.PullUpdate(task, row)
                localDirty -> Action.PushUpdate(task, remoteId)
                remoteDirty && differs(task, row) -> Action.PullUpdate(task, row)
                remoteDirty -> Action.Relink(task, row)
                else -> continue
            }
        }
        for (row in remote) {
            if (row.id !in linkedIds && !row.cancelled) actions += Action.PullCreate(row)
        }
        return actions
    }

    /** True if pulling [row] would change something the user sees. */
    fun differs(task: Task, row: RemoteTask): Boolean = applyRemote(task, row) != task

    /** The local task with the synced fields taken from [row]; local-only fields are kept. */
    fun applyRemote(task: Task, row: RemoteTask): Task {
        val (due, end) = if (task.isExam) (row.start ?: row.due) to row.due else row.due to task.endAtMillis
        return task.copy(
            title = row.title,
            note = row.description,
            dueAtMillis = due,
            endAtMillis = end,
            completed = row.completed,
            completedAtMillis = if (row.completed) row.completedAt ?: task.completedAtMillis else null,
            priority = fromRfcPriority(row.priority),
        )
    }

    /** A new local task for a remote one (not linked to a course). */
    fun newLocal(row: RemoteTask, now: Long): Task = Task(
        title = row.title,
        note = row.description,
        dueAtMillis = row.due,
        completed = row.completed,
        completedAtMillis = if (row.completed) row.completedAt ?: now else null,
        priority = fromRfcPriority(row.priority),
        createdAtMillis = now,
        modifiedAtMillis = now,
        remoteId = row.id,
        remoteModifiedAtMillis = row.lastModified,
    )

    /** Local 3 levels ↔ RFC 5545 (1 high, 5 medium, 9 low, 0 none). */
    fun toRfcPriority(priority: Int): Int = when (priority) {
        TaskPriority.HIGH -> 1
        TaskPriority.MEDIUM -> 5
        TaskPriority.LOW -> 9
        else -> 0
    }

    fun fromRfcPriority(priority: Int): Int = when (priority) {
        in 1..4 -> TaskPriority.HIGH
        5 -> TaskPriority.MEDIUM
        in 6..9 -> TaskPriority.LOW
        else -> TaskPriority.NONE
    }
}
