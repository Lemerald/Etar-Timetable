/*
 * Copyright (C) 2026 The Etar-Timetable project
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.android.calendar.timetable.data

/** Where a class meeting takes place, resolved from slot, building, campus and course. */
data class SlotPlace(
    val buildingName: String?,
    val room: String?,
    val address: String?,
    val campusName: String?,
) {
    /** Text for the calendar event's location, e.g. "Building A, 1.5, 77 Massachusetts Ave". */
    val eventLocation: String
        get() = listOfNotNull(buildingName, room, address).joinToString(", ")

    /** Compact label for the timetable grid and lists, e.g. "Building A 1.5". */
    val shortLabel: String?
        get() = listOfNotNull(buildingName, room).joinToString(" ").ifEmpty { null }
}

object SlotLocations {

    /**
     * Resolution rules:
     * - building: the slot's building, else the course's default building;
     * - room: the slot's room, else the course's default room, unless the slot moved
     *   to another building than the course's (that room would be in the wrong building);
     * - address: the building's address, else the address of the building's campus,
     *   else (no building) the term's campus address, else none.
     */
    fun resolve(
        buildingId: Long?,
        slotRoom: String?,
        course: Course?,
        term: Term?,
        campuses: Map<Long, Campus>,
        buildings: Map<Long, Building>,
    ): SlotPlace {
        val building = (buildingId ?: course?.buildingId)?.let(buildings::get)
        val courseRoomApplies = buildingId == null || course?.buildingId == null || buildingId == course.buildingId
        val campus = building?.let { campuses[it.campusId] } ?: term?.campusId?.let(campuses::get)
        return SlotPlace(
            buildingName = building?.name.clean(),
            room = slotRoom.clean() ?: course?.room.takeIf { courseRoomApplies }.clean(),
            address = building?.address.clean() ?: campus?.address.clean(),
            campusName = campus?.name.clean(),
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
