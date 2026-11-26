package com.android.calendar.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotLocationsTest {

    private val mit = Campus(1, "MIT", "77 Massachusetts Ave, Cambridge")
    private val noAddress = Campus(2, "Other Uni", "")
    private val buildingA = Building(10, 1, "Building A", "32 Vassar St, Cambridge")
    private val buildingB = Building(11, 1, "Building B", "")
    private val buildingX = Building(12, 2, "X", " ")
    private val campuses = listOf(mit, noAddress).associateBy { it.id }
    private val buildings = listOf(buildingA, buildingB, buildingX).associateBy { it.id }
    private val term = Term(1, "Fall", 0, 100, campusId = 1)
    private val course = Course(5, "Algorithms", room = "HS 1")

    private fun resolve(buildingId: Long?, room: String?, term: Term? = this.term, course: Course? = this.course) =
        SlotLocations.resolve(buildingId, room, course, term, campuses, buildings)

    @Test
    fun buildingAddressWins() {
        val place = resolve(10, "1.5")
        assertEquals("Building A, 1.5, 32 Vassar St, Cambridge", place.eventLocation)
        assertEquals("Building A 1.5", place.shortLabel)
    }

    @Test
    fun buildingWithoutAddressUsesItsCampus() {
        assertEquals("Building B, 1.5, 77 Massachusetts Ave, Cambridge", resolve(11, "1.5").eventLocation)
    }

    @Test
    fun noBuildingUsesTermCampusAndCourseRoom() {
        val place = resolve(null, null)
        assertEquals("HS 1, 77 Massachusetts Ave, Cambridge", place.eventLocation)
        assertEquals("HS 1", place.shortLabel)
        assertEquals("MIT", place.campusName)
    }

    @Test
    fun blankSlotRoomFallsBackToCourseRoom() {
        assertEquals("HS 1", resolve(null, "  ").room)
    }

    @Test
    fun nothingKnownMeansNoLocation() {
        val place = resolve(null, null, term = term.copy(campusId = null), course = course.copy(room = ""))
        assertEquals("", place.eventLocation)
        assertNull(place.shortLabel)
    }

    @Test
    fun campusWithoutAddressGivesNoAddress() {
        val place = resolve(12, "2", term = term.copy(campusId = 2))
        assertEquals("X, 2", place.eventLocation)
        assertNull(place.address)
    }

    @Test
    fun deletedBuildingIsIgnored() {
        assertEquals("HS 1, 77 Massachusetts Ave, Cambridge", resolve(99, null).eventLocation)
    }

    @Test
    fun courseDefaultBuildingAppliesToSlotsWithoutOne() {
        val withBuilding = course.copy(buildingId = 10)
        assertEquals("Building A, HS 1, 32 Vassar St, Cambridge", resolve(null, null, course = withBuilding).eventLocation)
        assertEquals("Building A, 2.1, 32 Vassar St, Cambridge", resolve(null, "2.1", course = withBuilding).eventLocation)
    }

    @Test
    fun slotInAnotherBuildingDoesNotInheritCourseRoom() {
        val withBuilding = course.copy(buildingId = 10)
        assertEquals("Building B, 77 Massachusetts Ave, Cambridge", resolve(11, null, course = withBuilding).eventLocation)
        assertEquals("Building A, HS 1, 32 Vassar St, Cambridge", resolve(10, null, course = withBuilding).eventLocation)
    }

    @Test
    fun courseRoomWithoutCourseBuildingStillApplies() {
        assertEquals("Building A, HS 1, 32 Vassar St, Cambridge", resolve(10, null).eventLocation)
    }
}
