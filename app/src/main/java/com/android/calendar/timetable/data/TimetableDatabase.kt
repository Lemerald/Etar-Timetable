package com.android.calendar.timetable.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Term::class,
        Course::class,
        CourseTerm::class,
        RegularSlot::class,
        OneTimeSlot::class,
        FreeDay::class,
        Task::class,
        GeneratedEvent::class,
        ChecklistItem::class,
        Campus::class,
        Building::class,
    ],
    version = 4,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
    exportSchema = true,
)
/*
 * Schema history is exported to app/schemas. The app now holds real user data:
 * every version bump MUST come with a Migration (no destructive fallback).
 */
abstract class TimetableDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao

    companion object {
        @Volatile
        private var instance: TimetableDatabase? = null

        fun get(context: Context): TimetableDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimetableDatabase::class.java,
                    "etar_timetable.db"
                ).build().also { instance = it }
            }
    }
}
