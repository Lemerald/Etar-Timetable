package com.android.calendar.timetable.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the upgrade path for every schema version ever shipped, not only the
 * newest step: a phone may skip several app updates and jump e.g. from v2 to the
 * latest version in one go.
 *
 * Versions are discovered from the exported schemas (app/schemas), so a new
 * version is covered automatically. Forgetting the migration for it, or the
 * exported schema, makes these tests fail.
 */
@RunWith(AndroidJUnit4::class)
class TimetableMigrationChainTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, TimetableDatabase::class.java)

    /** Versions with an exported schema, oldest first. */
    private val exportedVersions: List<Int> =
        instrumentation.context.assets.list(TimetableDatabase::class.java.canonicalName!!).orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }.sorted()

    private val latest get() = exportedVersions.last()

    @Test
    fun schemaHistoryIsCompleteAndMatchesTheCode() {
        // Oldest supported is v2 (v1 predates exported schemas and never reached users).
        assertEquals(2, exportedVersions.first())
        assertEquals("schema versions must have no gaps", (2..latest).toList(), exportedVersions)

        // The database the app builds is at the newest exported version, i.e. the
        // schema for the current @Database(version) has been exported and committed.
        val name = "version-check.db"
        val context = instrumentation.targetContext
        context.deleteDatabase(name)
        val db = TimetableDatabase.builder(context, name).build()
        try {
            assertEquals(latest, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun everyOldVersionUpgradesToLatestKeepingData() {
        val olderVersions = exportedVersions.dropLast(1)
        assertTrue("there should be older versions to test", olderVersions.isNotEmpty())
        for (version in olderVersions) {
            val name = "chain-from-$version.db"
            instrumentation.targetContext.deleteDatabase(name)
            helper.createDatabase(name, version).apply {
                // Columns that exist since v2; later versions add only columns with defaults.
                execSQL("INSERT INTO terms (id, name, startEpochDay, endEpochDay) VALUES (1, 'WS', 20740, 20860)")
                execSQL("INSERT INTO courses (id, name, teacher, room, color) VALUES (5, 'Algorithms', 'Prof. X', 'HS 1', -15444807)")
                execSQL("INSERT INTO course_terms (courseId, termId) VALUES (5, 1)")
                execSQL("INSERT INTO regular_slots (id, courseId, termId, weekday, startMinute, endMinute) VALUES (7, 5, 1, 1, 555, 645)")
                execSQL("INSERT INTO one_time_slots (id, courseId, termId, dateEpochDay, startMinute, endMinute) VALUES (8, 5, 1, 20800, 600, 660)")
                execSQL("INSERT INTO free_days (id, termId, dateEpochDay) VALUES (9, 1, 20810)")
                execSQL("INSERT INTO tasks (id, title, note, courseId, dueAtMillis, completed, completedAtMillis, createdAtMillis, calendarEventId) " +
                    "VALUES (11, 'Sheet 1', 'note', 5, 123, 0, NULL, 1, 99)")
                execSQL("INSERT INTO tasks (id, title, note, courseId, dueAtMillis, completed, completedAtMillis, createdAtMillis, calendarEventId) " +
                    "VALUES (12, 'Groceries', '', NULL, NULL, 1, 50, 2, NULL)")
                execSQL("INSERT INTO generated_events (id, termId, eventKey, eventId, calendarId, fingerprint, color, userEdited) " +
                    "VALUES (1, 1, 'regular:7:20741', 501, 1, 'fp', -15444807, 0)")
                close()
            }

            // Validates the migrated schema against the latest exported one.
            helper.runMigrationsAndValidate(name, latest, true).close()

            // Then read everything back through the app's own database setup.
            val db = TimetableDatabase.builder(instrumentation.targetContext, name).build()
            try {
                runBlocking {
                    val dao = db.timetableDao()
                    val term = dao.allTerms().single()
                    assertEquals("from v$version", "WS", term.name)
                    assertEquals(20860L, term.endEpochDay)
                    val course = dao.courses().single()
                    assertEquals("Algorithms", course.name)
                    assertEquals("HS 1", course.room)
                    assertEquals(-15444807, course.color)
                    assertEquals(1, dao.regularSlots(1).size)
                    assertEquals(555, dao.regularSlots(1).single().startMinute)
                    assertEquals(1, dao.oneTimeSlots(1).size)
                    assertEquals(1, dao.freeDays(1).size)
                    val tasks = dao.allTasks().sortedBy { it.id }
                    assertEquals(listOf("Sheet 1", "Groceries"), tasks.map { it.title })
                    assertEquals(5L, tasks[0].courseId)
                    assertEquals(123L, tasks[0].dueAtMillis)
                    assertEquals(99L, tasks[0].calendarEventId)
                    assertTrue(tasks[1].completed)
                    assertEquals(501L, dao.generatedEvents(1).single().eventId)
                }
            } finally {
                db.close()
                instrumentation.targetContext.deleteDatabase(name)
            }
        }
    }
}
