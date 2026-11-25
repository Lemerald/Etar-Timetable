package com.android.calendar.timetable.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every schema version must migrate without losing data. Add a test case here for
 * each new version (schemas live in app/schemas).
 */
@RunWith(AndroidJUnit4::class)
class TimetableMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TimetableDatabase::class.java,
    )

    @Test
    fun migrate2To3KeepsData() {
        helper.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO terms (id, name, startEpochDay, endEpochDay) VALUES (1, 'WS', 20740, 20860)")
            execSQL("INSERT INTO courses (id, name, teacher, room, color) VALUES (5, 'Algorithms', 'X', 'HS 1', -15444807)")
            execSQL("INSERT INTO course_terms (courseId, termId) VALUES (5, 1)")
            execSQL("INSERT INTO regular_slots (id, courseId, termId, weekday, startMinute, endMinute) VALUES (7, 5, 1, 1, 555, 645)")
            execSQL("INSERT INTO tasks (id, title, note, courseId, dueAtMillis, completed, completedAtMillis, createdAtMillis, calendarEventId) " +
                "VALUES (11, 'Sheet 1', 'n', 5, 123, 0, NULL, 1, 99)")
            close()
        }

        helper.runMigrationsAndValidate(dbName, 3, true).close()

        // Open with Room itself to check the entities read back with the new defaults.
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TimetableDatabase::class.java, dbName,
        ).build()
        try {
            runBlocking {
                val dao = db.timetableDao()
                val task = dao.allTasks().single()
                assertEquals("Sheet 1", task.title)
                assertEquals(99L, task.calendarEventId)
                assertEquals(TaskKind.TASK, task.kind)
                assertEquals(TaskPriority.NONE, task.priority)
                assertNull(task.repeatRule)
                assertNull(task.remoteId)
                val course = dao.courses().single()
                assertEquals("Algorithms", course.name)
                assertNull(course.startEpochDay)
                assertEquals(1, dao.regularSlots(1).size)
                assertEquals(0, dao.allChecklistItems().size)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3To4KeepsData() {
        helper.createDatabase(dbName, 3).apply {
            execSQL("INSERT INTO terms (id, name, startEpochDay, endEpochDay) VALUES (1, 'WS', 20740, 20860)")
            execSQL("INSERT INTO courses (id, name, teacher, room, color) VALUES (5, 'Algorithms', 'X', 'HS 1', -15444807)")
            execSQL("INSERT INTO regular_slots (id, courseId, termId, weekday, startMinute, endMinute) VALUES (7, 5, 1, 1, 555, 645)")
            execSQL("INSERT INTO one_time_slots (id, courseId, termId, dateEpochDay, startMinute, endMinute) VALUES (8, 5, 1, 20800, 600, 660)")
            close()
        }

        helper.runMigrationsAndValidate(dbName, 4, true).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TimetableDatabase::class.java, dbName,
        ).build()
        try {
            runBlocking {
                val dao = db.timetableDao()
                assertNull(dao.term(1)!!.campusId)
                val slot = dao.regularSlots(1).single()
                assertEquals(555, slot.startMinute)
                assertNull(slot.buildingId)
                assertNull(slot.room)
                assertNull(dao.oneTimeSlots(1).single().buildingId)
                assertEquals(0, dao.campuses().size)
                // New tables are usable.
                val campus = dao.insertCampus(Campus(name = "MIT", address = "77 Massachusetts Ave"))
                dao.insertBuilding(Building(campusId = campus, name = "A"))
                assertEquals(1, dao.buildings().size)
            }
        } finally {
            db.close()
        }
    }
}
