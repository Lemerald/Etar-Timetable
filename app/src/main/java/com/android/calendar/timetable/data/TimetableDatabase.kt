package com.android.calendar.timetable.data

import android.content.Context
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
    ],
    version = 2,
    exportSchema = false,
)
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
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
