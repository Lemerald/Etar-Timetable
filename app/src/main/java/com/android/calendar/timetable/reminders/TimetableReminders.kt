/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.calendar.AllInOneActivity
import com.android.calendar.timetable.data.TimetableRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ws.xsoh.etar.R

/** Reminder preferences. Minutes values of [OFF] disable a reminder. */
object ReminderSettings {
    const val OFF = -1
    private const val PREFS = "etar_timetable_prefs"
    private const val KEY_CLASS = "reminder_class_minutes"
    private const val KEY_TASK = "reminder_task_minutes"
    private const val KEY_EXAM = "reminder_exam_minutes"
    private const val KEY_SUMMARY = "reminder_due_summary"

    /** Choices offered in the UI, in minutes. */
    val CLASS_CHOICES = listOf(OFF, 5, 10, 15, 30, 60)
    val TASK_CHOICES = listOf(OFF, 15, 60, 180, 1440)
    val EXAM_CHOICES = listOf(OFF, 60, 1440, 2880, 10080)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun classMinutes(context: Context) = prefs(context).getInt(KEY_CLASS, OFF)
    fun taskMinutes(context: Context) = prefs(context).getInt(KEY_TASK, 60)
    fun examMinutes(context: Context) = prefs(context).getInt(KEY_EXAM, 1440)
    fun dueSummary(context: Context) = prefs(context).getBoolean(KEY_SUMMARY, true)

    fun set(context: Context, classMinutes: Int, taskMinutes: Int, examMinutes: Int, dueSummary: Boolean) {
        prefs(context).edit()
            .putInt(KEY_CLASS, classMinutes).putInt(KEY_TASK, taskMinutes)
            .putInt(KEY_EXAM, examMinutes).putBoolean(KEY_SUMMARY, dueSummary)
            .apply()
    }
}

/**
 * The "due tomorrow" summary notification: scheduled with AlarmManager for
 * [DueSummaryPlanner.nextTrigger] and rescheduled after each run, on boot, on time
 * changes and whenever the timetable changes.
 */
object DueSummary {

    private const val TAG = "DueSummary"
    private const val CHANNEL_ID = "timetable_due_summary"
    private const val NOTIFICATION_ID = 0x7417
    private const val ACTION_FIRE = "com.android.calendar.timetable.DUE_SUMMARY"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    @JvmStatic
    fun schedule(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                scheduleNow(app)
            } catch (e: Exception) {
                Log.e(TAG, "Scheduling failed", e)
            }
        }
    }

    private suspend fun scheduleNow(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        if (!ReminderSettings.dueSummary(context)) return
        val data = TimetableRepository.get(context).data.first()
        val trigger = DueSummaryPlanner.nextTrigger(data, LocalDateTime.now())
        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
        Log.d(TAG, "Next due summary at $trigger")
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DueSummaryReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal suspend fun post(context: Context) {
        val data = TimetableRepository.get(context).data.first()
        val tomorrow = LocalDate.now().plusDays(1)
        val due = DueSummaryPlanner.dueOn(data.tasks, tomorrow, System.currentTimeMillis())
        if (due.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.timetable_due_summary_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val lines = due.map { task ->
            val time = Instant.ofEpochMilli(task.dueAtMillis!!).atZone(ZoneId.systemDefault()).toLocalTime()
            val course = task.courseId?.let { data.courseById(it)?.name }
            val prefix = if (task.isExam) context.getString(R.string.timetable_exam_event_prefix, task.title) else task.title
            listOfNotNull(time.format(TIME_FORMAT), prefix, course).joinToString(" · ")
        }
        val title = context.resources.getQuantityString(R.plurals.timetable_due_summary_title, due.size, due.size)
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, AllInOneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_notify_calendar_events)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** Fires the summary, and reschedules after boot / time changes. */
class DueSummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (intent.action == "com.android.calendar.timetable.DUE_SUMMARY") DueSummary.post(app)
            } catch (e: Exception) {
                Log.e("DueSummary", "Posting failed", e)
            } finally {
                DueSummary.schedule(app)
                result.finish()
            }
        }
    }
}
