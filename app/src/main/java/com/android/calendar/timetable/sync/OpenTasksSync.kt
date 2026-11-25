/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.android.calendar.timetable.data.Task
import com.android.calendar.timetable.data.TimetableRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Bridge between local tasks and one task list of an OpenTasks-compatible provider
 * (Tasks.org or OpenTasks), which DAVx5 syncs as CalDAV VTODOs (US-5.3). The app works
 * fully without it; nothing here runs unless the user picked a list.
 */
object OpenTasksSync {

    private const val TAG = "OpenTasksSync"

    /** OpenTasks providers we know, in order of preference. */
    data class Provider(val authority: String, val readPermission: String, val writePermission: String, val appName: String) {
        val permissions get() = arrayOf(readPermission, writePermission)
        fun uri(path: String): Uri = Uri.parse("content://$authority/$path")
    }

    val PROVIDERS = listOf(
        Provider("org.tasks.opentasks", "org.tasks.permission.READ_TASKS", "org.tasks.permission.WRITE_TASKS", "Tasks.org"),
        Provider("org.dmfs.tasks", "org.dmfs.permission.READ_TASKS", "org.dmfs.permission.WRITE_TASKS", "OpenTasks"),
    )

    data class TaskList(val id: Long, val name: String, val account: String, val accountType: String)

    // ---- OpenTasks contract (subset; see reference/opentasks/TaskContract.java) ----
    private const val ID = "_id"
    private const val LIST_ID = "list_id"
    private const val LIST_NAME = "list_name"
    private const val ACCOUNT_NAME = "account_name"
    private const val ACCOUNT_TYPE = "account_type"
    private const val SYNC_ENABLED = "sync_enabled"
    private const val TITLE = "title"
    private const val DESCRIPTION = "description"
    private const val DTSTART = "dtstart"
    private const val DUE = "due"
    private const val TZ = "tz"
    private const val IS_ALLDAY = "is_allday"
    private const val STATUS = "status"
    private const val COMPLETED = "completed"
    private const val COMPLETED_IS_ALLDAY = "completed_is_allday"
    private const val PERCENT_COMPLETE = "percent_complete"
    private const val PRIORITY = "priority"
    private const val LAST_MODIFIED = "last_modified"
    private const val TASK_ID = "task_id"
    private const val MIMETYPE = "mimetype"
    private const val CATEGORY_NAME = "data1"
    private const val CATEGORY_MIMETYPE = "vnd.android.cursor.item/category"
    private const val STATUS_NEEDS_ACTION = 0
    private const val STATUS_COMPLETED = 2
    private const val STATUS_CANCELLED = 3

    // ---- Settings ----------------------------------------------------------
    private const val PREFS = "etar_timetable_prefs"
    private const val KEY_AUTHORITY = "sync_authority"
    private const val KEY_LIST_ID = "sync_list_id"
    private const val KEY_LIST_NAME = "sync_list_name"
    private const val KEY_ACCOUNT_NAME = "sync_account_name"
    private const val KEY_ACCOUNT_TYPE = "sync_account_type"
    private const val KEY_LAST_SYNC = "sync_last_millis"
    private const val KEY_LAST_RESULT = "sync_last_result"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(context: Context): Provider? =
        prefs(context).getString(KEY_AUTHORITY, null)?.let { a -> PROVIDERS.firstOrNull { it.authority == a } }

    fun listId(context: Context): Long? = prefs(context).getLong(KEY_LIST_ID, -1L).takeIf { it != -1L }
    fun listName(context: Context): String? = prefs(context).getString(KEY_LIST_NAME, null)
    fun lastSync(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)
    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST_RESULT, null)
    fun isEnabled(context: Context) = provider(context) != null && listId(context) != null

    fun installedProviders(context: Context): List<Provider> =
        PROVIDERS.filter { context.packageManager.resolveContentProvider(it.authority, 0) != null }

    fun hasPermissions(context: Context, provider: Provider) = provider.permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Task lists of [provider] that are synced by an account (e.g. DAVx5). */
    fun lists(context: Context, provider: Provider): List<TaskList> {
        val result = ArrayList<TaskList>()
        context.contentResolver.query(provider.uri("tasklists"), arrayOf(ID, LIST_NAME, ACCOUNT_NAME, ACCOUNT_TYPE),
            "$SYNC_ENABLED=1", null, "$ACCOUNT_NAME, $LIST_NAME")?.use { c ->
            while (c.moveToNext()) {
                result += TaskList(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty())
            }
        }
        return result
    }

    /** Starts syncing with [list]. Switching lists drops all old links (tasks are pushed again). */
    suspend fun choose(context: Context, provider: Provider, list: TaskList) {
        val app = context.applicationContext
        val switching = provider(app)?.authority != provider.authority || listId(app) != list.id
        if (switching) TimetableRepository.get(app).clearRemoteLinks()
        prefs(app).edit()
            .putString(KEY_AUTHORITY, provider.authority).putLong(KEY_LIST_ID, list.id)
            .putString(KEY_LIST_NAME, "${list.name} (${list.account})")
            .putString(KEY_ACCOUNT_NAME, list.account).putString(KEY_ACCOUNT_TYPE, list.accountType)
            .apply { if (switching) putLong(KEY_LAST_SYNC, 0L) }
            .apply()
        start(app)
        sync(app)
    }

    suspend fun disable(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().remove(KEY_AUTHORITY).remove(KEY_LIST_ID).remove(KEY_LIST_NAME)
            .remove(KEY_ACCOUNT_NAME).remove(KEY_ACCOUNT_TYPE)
            .remove(KEY_LAST_SYNC).remove(KEY_LAST_RESULT).apply()
        TimetableRepository.get(app).clearRemoteLinks()
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        observer?.let { app.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    // ---- Triggers ----------------------------------------------------------
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var observer: ContentObserver? = null
    private const val WORK_NAME = "timetable_task_sync"
    private const val DEBOUNCE_MS = 3_000L

    /** Call at app start: observes provider changes and schedules the periodic sync. */
    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        val provider = provider(app) ?: return
        if (!hasPermissions(app, provider)) return
        if (observer == null) {
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = requestSync(app)
            }.also {
                try {
                    app.contentResolver.registerContentObserver(provider.uri("tasks"), true, it)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot observe ${provider.authority}", e)
                }
            }
        }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TaskSyncWorker>(1, TimeUnit.HOURS).build())
        requestSync(app)
    }

    /** Debounced sync after local or remote changes. */
    @JvmStatic
    fun requestSync(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS) // only the wait is cancelled by newer requests, never a running sync
            withContext(NonCancellable) { sync(app) }
        }
    }

    /** Deletes the remote copy of a locally deleted task. */
    fun deleteRemote(context: Context, remoteId: Long) {
        val provider = provider(context) ?: return
        if (!hasPermissions(context, provider)) return
        try {
            context.contentResolver.delete(Uri.withAppendedPath(provider.uri("tasks"), remoteId.toString()), null, null)
            requestUpload(context, provider)
        } catch (e: Exception) {
            Log.w(TAG, "Remote delete failed", e)
        }
    }

    // ---- The sync run --------------------------------------------------------

    /** Runs one sync. Returns a short summary, or null if sync is not set up. */
    suspend fun sync(context: Context): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val provider = provider(app) ?: return@withContext null
        val listId = listId(app) ?: return@withContext null
        if (!hasPermissions(app, provider)) return@withContext null
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            val repository = TimetableRepository.get(app)
            val result = try {
                val remote = queryList(app.contentResolver, provider, listId)
                val local = repository.allTasks()
                val actions = TaskSyncEngine.plan(local, remote, lastSync(app))
                val courses = repository.coursesOnce().associate { it.id to it.name }
                var pushed = 0
                var pulled = 0
                for (action in actions) when (action) {
                    is TaskSyncEngine.Action.PushCreate -> {
                        val id = insertRemote(app.contentResolver, provider, listId, action.task, courses)
                        repository.linkRemote(action.task.id, id, lastModified(app.contentResolver, provider, id))
                        pushed++
                    }
                    is TaskSyncEngine.Action.PushUpdate -> {
                        updateRemote(app.contentResolver, provider, action.remoteId, action.task, courses)
                        repository.linkRemote(action.task.id, action.remoteId,
                            lastModified(app.contentResolver, provider, action.remoteId))
                        pushed++
                    }
                    is TaskSyncEngine.Action.PullUpdate -> {
                        repository.saveSyncedTask(TaskSyncEngine.applyRemote(action.task, action.remote).copy(
                            modifiedAtMillis = startedAt, remoteModifiedAtMillis = action.remote.lastModified))
                        pulled++
                    }
                    is TaskSyncEngine.Action.PullCreate -> {
                        repository.saveSyncedTask(TaskSyncEngine.newLocal(action.remote, startedAt))
                        pulled++
                    }
                    is TaskSyncEngine.Action.DeleteLocal -> {
                        repository.deleteLocalOnly(action.task)
                        pulled++
                    }
                    is TaskSyncEngine.Action.Relink ->
                        repository.linkRemote(action.task.id, action.remote.id, action.remote.lastModified)
                }
                prefs(app).edit().putLong(KEY_LAST_SYNC, startedAt).apply()
                if (pushed > 0) requestUpload(app, provider)
                "↑$pushed ↓$pulled"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                "error: ${e.javaClass.simpleName}: ${e.message}"
            }
            prefs(app).edit().putString(KEY_LAST_RESULT, result).apply()
            Log.d(TAG, "Sync finished: $result")
            result
        }
    }

    /**
     * Asks the sync adapter owning the list (DAVx5) to upload now instead of at its next
     * interval, like Tasks.org does.
     */
    private fun requestUpload(context: Context, provider: Provider) {
        val name = prefs(context).getString(KEY_ACCOUNT_NAME, null) ?: return
        val type = prefs(context).getString(KEY_ACCOUNT_TYPE, null) ?: return
        try {
            ContentResolver.requestSync(Account(name, type), provider.authority, Bundle().apply {
                // MANUAL like Tasks.org: otherwise ignored when the account's auto-sync is off.
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not request upload", e)
        }
    }

    private fun queryList(resolver: ContentResolver, provider: Provider, listId: Long): List<RemoteTask> {
        val projection = arrayOf(ID, TITLE, DESCRIPTION, DTSTART, DUE, IS_ALLDAY, STATUS, COMPLETED, PRIORITY, LAST_MODIFIED)
        val rows = ArrayList<RemoteTask>()
        resolver.query(provider.uri("tasks"), projection, "$LIST_ID=?", arrayOf(listId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) == 1
                val status = c.getInt(6)
                rows += RemoteTask(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    description = c.getString(2).orEmpty(),
                    start = c.longOrNull(3)?.let { if (allDay) allDayToLocal(it) else it },
                    due = c.longOrNull(4)?.let { if (allDay) allDayToLocal(it) else it },
                    completed = status == STATUS_COMPLETED,
                    completedAt = c.longOrNull(7),
                    priority = c.getInt(8),
                    lastModified = c.longOrNull(9) ?: 0L,
                    cancelled = status == STATUS_CANCELLED,
                )
            }
        }
        return rows
    }

    /** All-day values are UTC midnight of the date; locally a task due "on a day" is due by its end. */
    private fun allDayToLocal(utcMidnight: Long): Long =
        Instant.ofEpochMilli(utcMidnight).atOffset(ZoneOffset.UTC).toLocalDate()
            .atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun values(task: Task): ContentValues = ContentValues().apply {
        put(TITLE, task.title)
        put(DESCRIPTION, task.note)
        val start = if (task.isExam) task.dueAtMillis else null
        val due = if (task.isExam) task.endAtMillis ?: task.dueAtMillis else task.dueAtMillis
        if (start != null) put(DTSTART, start) else putNull(DTSTART)
        if (due != null) put(DUE, due) else putNull(DUE)
        if (start != null || due != null) put(TZ, ZoneId.systemDefault().id)
        put(IS_ALLDAY, 0)
        put(PRIORITY, TaskSyncEngine.toRfcPriority(task.priority))
        if (task.completed) {
            put(STATUS, STATUS_COMPLETED)
            put(COMPLETED, task.completedAtMillis ?: System.currentTimeMillis())
            put(COMPLETED_IS_ALLDAY, 0)
            put(PERCENT_COMPLETE, 100)
        } else {
            put(STATUS, STATUS_NEEDS_ACTION)
            putNull(COMPLETED)
            putNull(PERCENT_COMPLETE)
        }
    }

    private fun insertRemote(resolver: ContentResolver, provider: Provider, listId: Long, task: Task,
                             courses: Map<Long, String>): Long {
        val uri = resolver.insert(provider.uri("tasks"), values(task).apply { put(LIST_ID, listId) })
            ?: throw IllegalStateException("Provider refused task insert")
        val id = uri.lastPathSegment!!.toLong()
        setCategory(resolver, provider, id, task.courseId?.let(courses::get))
        return id
    }

    private fun updateRemote(resolver: ContentResolver, provider: Provider, id: Long, task: Task,
                             courses: Map<Long, String>) {
        resolver.update(Uri.withAppendedPath(provider.uri("tasks"), id.toString()), values(task), null, null)
        setCategory(resolver, provider, id, task.courseId?.let(courses::get))
    }

    /** The course name becomes the VTODO's CATEGORIES, so other apps see which course it belongs to. */
    private fun setCategory(resolver: ContentResolver, provider: Provider, taskId: Long, name: String?) {
        val properties = provider.uri("properties")
        resolver.delete(properties, "$TASK_ID=? AND $MIMETYPE=?", arrayOf(taskId.toString(), CATEGORY_MIMETYPE))
        if (name != null) {
            resolver.insert(properties, ContentValues().apply {
                put(TASK_ID, taskId)
                put(MIMETYPE, CATEGORY_MIMETYPE)
                put(CATEGORY_NAME, name)
            })
        }
    }

    private fun lastModified(resolver: ContentResolver, provider: Provider, id: Long): Long =
        resolver.query(Uri.withAppendedPath(provider.uri("tasks"), id.toString()), arrayOf(LAST_MODIFIED),
            null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
}

/** Hourly safety net, in case changes arrived while the app was not running. */
class TaskSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        OpenTasksSync.sync(applicationContext)
        return Result.success()
    }
}
