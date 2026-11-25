/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.android.calendar.timetable.data.TimetableRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Which backup files to keep: the newest [keep] by date in the file name. Pure, unit tested. */
object BackupRetention {
    // Also matches copies a provider renamed on a name clash, e.g. "… (1).json".
    private val NAME = Regex("""timetable-backup-(\d{4}-\d{2}-\d{2})( \(\d+\))?\.json""")

    fun fileName(date: LocalDate) = "timetable-backup-$date.json"

    fun isBackupName(name: String) = NAME.matches(name)

    /** Names of backup files to delete; other files in the folder are never touched. */
    fun toDelete(names: List<String>, keep: Int): List<String> =
        names.distinct().filter(::isBackupName)
            // Newest date first; for the same date the plain name before "(n)" copies.
            .sortedWith(compareByDescending<String> { NAME.matchEntire(it)!!.groupValues[1] }.thenBy { it.length })
            .drop(keep)
}

/**
 * Daily automatic JSON backups into a folder the user picked with the system folder
 * picker. Any documents provider works: local storage, or e.g. the Nextcloud app, which
 * then uploads the file — this app never handles cloud credentials.
 */
object AutoBackup {

    private const val TAG = "AutoBackup"
    private const val PREFS = "etar_timetable_prefs"
    private const val KEY_FOLDER = "auto_backup_folder"
    private const val KEY_LAST_SUCCESS = "auto_backup_last_success"
    private const val KEY_LAST_ERROR = "auto_backup_last_error"
    private const val WORK_DAILY = "timetable_auto_backup"
    private const val WORK_NOW = "timetable_auto_backup_now"
    const val KEEP = 14

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)
    fun lastSuccess(context: Context): Long = prefs(context).getLong(KEY_LAST_SUCCESS, 0L)
    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    /** Human-readable folder name as the provider shows it. */
    fun folderName(context: Context): String? {
        val tree = folder(context) ?: return null
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: tree.lastPathSegment
    }

    /** Enables daily backups into [tree] (from ACTION_OPEN_DOCUMENT_TREE) and runs one now. */
    fun enable(context: Context, tree: Uri) {
        val app = context.applicationContext
        app.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        folder(app)?.takeIf { it != tree }?.let { old -> releasePermission(app, old) }
        prefs(app).edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_LAST_ERROR).apply()
        schedule(app)
        runNow(app)
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        folder(app)?.let { releasePermission(app, it) }
        prefs(app).edit().remove(KEY_FOLDER).remove(KEY_LAST_ERROR).apply()
        WorkManager.getInstance(app).cancelUniqueWork(WORK_DAILY)
    }

    private fun releasePermission(context: Context, tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone.
        }
    }

    /** Idempotent; called at app start. */
    @JvmStatic
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (folder(app) == null) return
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build())
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    private val mutex = Mutex()

    /** Writes today's backup (replacing an earlier one from today) and prunes old ones. */
    suspend fun backup(context: Context): Boolean = mutex.withLock { backupLocked(context) }

    private suspend fun backupLocked(context: Context): Boolean {
        val app = context.applicationContext
        val tree = folder(app) ?: return false
        return try {
            val json = TimetableRepository.get(app).exportBackupJson()
            val resolver = app.contentResolver
            val treeDoc = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc)
            val existing = HashMap<String, String>() // name -> document id
            resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c -> while (c.moveToNext()) existing[c.getString(1)] = c.getString(0) }

            val name = BackupRetention.fileName(LocalDate.now())
            val target = existing[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                ?: DocumentsContract.createDocument(resolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc), "application/json", name)
                ?: throw IllegalStateException("Cannot create $name")
            resolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) }
                ?: throw IllegalStateException("Cannot write $name")

            for (old in BackupRetention.toDelete(existing.keys.toList() + name, KEEP)) {
                existing[old]?.let { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
            }
            prefs(app).edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Automatic backup failed", e)
            prefs(app).edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
            false
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (AutoBackup.backup(applicationContext) || runAttemptCount >= 3) Result.success() else Result.retry()
}
