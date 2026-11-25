package com.android.calendar.timetable

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.calendar.timetable.data.GeneratedEvent
import com.android.calendar.timetable.data.TimetableRepository
import kotlinx.coroutines.launch

/** Java-friendly bridge for checking whether a calendar event came from the timetable. */
object GeneratedEvents {
    fun interface Callback {
        fun onResult(event: GeneratedEvent?)
    }

    @JvmStatic
    fun check(activity: FragmentActivity, eventId: Long, callback: Callback) {
        activity.lifecycleScope.launch {
            callback.onResult(
                if (eventId > 0) TimetableRepository.get(activity).generatedEventFor(eventId) else null
            )
        }
    }
}
