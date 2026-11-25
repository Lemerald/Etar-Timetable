package com.android.calendar.timetable.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {

    // ---- Observed data for the timetable view -----------------------------
    @Query("SELECT * FROM terms ORDER BY startEpochDay DESC, name")
    fun observeTerms(): Flow<List<Term>>

    @Query("SELECT * FROM courses ORDER BY name")
    fun observeCourses(): Flow<List<Course>>

    @Query("SELECT * FROM course_terms")
    fun observeCourseTerms(): Flow<List<CourseTerm>>

    @Query("SELECT * FROM regular_slots ORDER BY weekday, startMinute")
    fun observeRegularSlots(): Flow<List<RegularSlot>>

    @Query("SELECT * FROM one_time_slots ORDER BY dateEpochDay, startMinute")
    fun observeOneTimeSlots(): Flow<List<OneTimeSlot>>

    @Query("SELECT * FROM free_days")
    fun observeFreeDays(): Flow<List<FreeDay>>

    @Query("SELECT * FROM tasks ORDER BY dueAtMillis IS NULL, dueAtMillis, title")
    fun observeTasks(): Flow<List<Task>>

    // ---- One-shot reads ---------------------------------------------------
    @Query("SELECT * FROM terms WHERE id = :id")
    suspend fun term(id: Long): Term?

    @Query("SELECT * FROM courses ORDER BY name")
    suspend fun courses(): List<Course>

    @Query("SELECT * FROM regular_slots WHERE termId = :termId")
    suspend fun regularSlots(termId: Long): List<RegularSlot>

    @Query("SELECT * FROM one_time_slots WHERE termId = :termId")
    suspend fun oneTimeSlots(termId: Long): List<OneTimeSlot>

    @Query("SELECT * FROM free_days WHERE termId = :termId")
    suspend fun freeDays(termId: Long): List<FreeDay>

    @Query("SELECT * FROM course_terms WHERE termId = :termId")
    suspend fun courseTerms(termId: Long): List<CourseTerm>

    @Query("SELECT * FROM generated_events WHERE termId = :termId")
    suspend fun generatedEvents(termId: Long): List<GeneratedEvent>

    @Query("SELECT * FROM generated_events WHERE eventId = :eventId LIMIT 1")
    suspend fun generatedEventByEventId(eventId: Long): GeneratedEvent?

    @Query("SELECT * FROM tasks WHERE dueAtMillis IS NOT NULL AND completed = 0")
    suspend fun tasksWithDueDate(): List<Task>

    // ---- Writes -----------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerm(term: Term): Long

    @Update
    suspend fun updateTerm(term: Term)

    @Delete
    suspend fun deleteTerm(term: Term)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Update
    suspend fun updateCourse(course: Course)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourseTerm(link: CourseTerm)

    @Query("DELETE FROM course_terms WHERE courseId = :courseId AND termId = :termId")
    suspend fun unlinkCourse(courseId: Long, termId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegularSlot(slot: RegularSlot): Long

    @Update
    suspend fun updateRegularSlot(slot: RegularSlot)

    @Delete
    suspend fun deleteRegularSlot(slot: RegularSlot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOneTimeSlot(slot: OneTimeSlot): Long

    @Update
    suspend fun updateOneTimeSlot(slot: OneTimeSlot)

    @Delete
    suspend fun deleteOneTimeSlot(slot: OneTimeSlot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFreeDay(freeDay: FreeDay): Long

    @Delete
    suspend fun deleteFreeDay(freeDay: FreeDay)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeneratedEvent(event: GeneratedEvent): Long

    @Query("DELETE FROM generated_events WHERE eventKey = :key")
    suspend fun deleteGeneratedEventByKey(key: String)

    @Query("DELETE FROM generated_events WHERE termId = :termId")
    suspend fun deleteGeneratedEventsForTerm(termId: Long)

    // ---- Backup / restore -------------------------------------------------
    @Query("SELECT * FROM generated_events")
    suspend fun allGeneratedEvents(): List<GeneratedEvent>

    @Query("SELECT * FROM tasks")
    suspend fun allTasks(): List<Task>

    @Query("SELECT * FROM terms")
    suspend fun allTerms(): List<Term>

    @Query("DELETE FROM generated_events")
    suspend fun deleteAllGeneratedEvents()

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM free_days")
    suspend fun deleteAllFreeDays()

    @Query("DELETE FROM one_time_slots")
    suspend fun deleteAllOneTimeSlots()

    @Query("DELETE FROM regular_slots")
    suspend fun deleteAllRegularSlots()

    @Query("DELETE FROM course_terms")
    suspend fun deleteAllCourseTerms()

    @Query("DELETE FROM courses")
    suspend fun deleteAllCourses()

    @Query("DELETE FROM terms")
    suspend fun deleteAllTerms()

    @Insert suspend fun insertTerms(items: List<Term>)
    @Insert suspend fun insertCourses(items: List<Course>)
    @Insert suspend fun insertCourseTerms(items: List<CourseTerm>)
    @Insert suspend fun insertRegularSlots(items: List<RegularSlot>)
    @Insert suspend fun insertOneTimeSlots(items: List<OneTimeSlot>)
    @Insert suspend fun insertFreeDays(items: List<FreeDay>)
    @Insert suspend fun insertTasks(items: List<Task>)
}
