package de.mm20.launcher2.ui.launcher.widgets.calendar

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.mm20.launcher2.calendar.CalendarRepository
import de.mm20.launcher2.ktx.tryStartActivity
import de.mm20.launcher2.search.CalendarEvent
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.services.favorites.FavoritesService
import de.mm20.launcher2.widgets.CalendarWidget
import de.mm20.launcher2.widgets.CalendarWidgetConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.Integer.min
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.max

class CalendarWidgetVM : ViewModel(), KoinComponent {

    private val calendarRepository: CalendarRepository by inject()
    private val favoritesService: FavoritesService by inject()
    private val widgetConfig = MutableStateFlow(CalendarWidgetConfig())

    val calendarEvents = mutableStateOf<List<CalendarEvent>>(emptyList())
    val pinnedCalendarEvents =
        favoritesService.getFavorites(
            includeTypes = listOf("google.calendar"),
            minPinnedLevel = PinnedLevel.AutomaticallySorted,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    val nextEvents = mutableStateOf<List<CalendarEvent>>(emptyList())
    var availableDates = listOf(LocalDate.now())

    private var showRunningPastDayEvents = false
    private var upcomingEventsCount = 3
    val hiddenPastEvents = mutableStateOf(0)

    val selectedDate = mutableStateOf(LocalDate.now())

    fun updateWidget(widget: CalendarWidget) {
        widgetConfig.value = widget.config
        upcomingEventsCount = widget.config.upcomingEventsCount.coerceIn(1, 10)
    }

    private var upcomingEvents: List<CalendarEvent> = emptyList()
        set(value) {
            field = value
            val dates = value.flatMap {
                val startDate =
                    it.startTime?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                val endDate =
                    Instant.ofEpochMilli(it.endTime).atZone(ZoneId.systemDefault()).toLocalDate()
                return@flatMap listOfNotNull(
                    startDate,
                    endDate
                )
            }.union(listOf(LocalDate.now()))
                .distinct()
                .sorted()
            availableDates = dates
            val date = selectedDate.value?.takeIf { dates.contains(it) } ?: LocalDate.now()
            selectDate(date)
        }


    fun nextDay() {
        val dates = availableDates
        val date = selectedDate.value ?: return
        val currentIndex = dates.indexOf(date)
        val index = min(currentIndex + 1, dates.lastIndex)
        selectDate(dates[index])
    }

    fun previousDay() {
        val dates = availableDates
        val date = selectedDate.value ?: return
        val currentIndex = dates.indexOf(date)
        val index = max(currentIndex - 1, 0)
        selectDate(dates[index])
    }

    fun selectDate(date: LocalDate) {
        val dates = availableDates
        showRunningPastDayEvents = false
        if (dates.contains(date)) {
            selectedDate.value = date
            updateEvents()
        }
    }

    fun showAllEvents() {
        showRunningPastDayEvents = true
        updateEvents()
    }


    fun createEvent(context: Context) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .setPackage("com.google.android.calendar")
        val zoneOffset = OffsetDateTime.now().offset
        val beginTime = selectedDate.value.atTime(12, 0).toInstant(zoneOffset).toEpochMilli()
        val endTime = selectedDate.value.atTime(13, 0).toInstant(zoneOffset).toEpochMilli()
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.tryStartActivity(intent)
    }

    fun openCalendarApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        context.tryStartActivity(intent)
    }

    private fun updateEvents() {
        val date = selectedDate.value ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val dayStart = max(now, date.atStartOfDay(zone).toInstant().toEpochMilli())
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var events = upcomingEvents.filter {
            it.endTime >= dayStart && (it.startTime ?: 0L) < dayEnd
        }

        val startOfDay = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfNextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        if (!showRunningPastDayEvents) {
            val totalCount = events.size


            events = events.filter {
                ((it.startTime != null && it.startTime!! >= startOfDay) ||
                        it.endTime < startOfNextDay)
            }

            val hiddenCount = totalCount - events.size
            hiddenPastEvents.value = hiddenCount
        } else {
            hiddenPastEvents.value = 0
        }


        calendarEvents.value = events

        val visibleEventKeys = events.mapTo(mutableSetOf()) { it.key }
        nextEvents.value = upcomingEvents
            .asSequence()
            .filter { !it.isTask }
            .filter { it.key !in visibleEventKeys }
            .filter { now < (it.startTime ?: it.endTime) }
            .sortedBy { it.startTime ?: it.endTime }
            .take(upcomingEventsCount)
            .toList()
    }

    suspend fun onActive() {
        selectDate(LocalDate.now())
        widgetConfig.collectLatest { config ->
            calendarRepository.findMany(
                from = LocalDate.now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
                to = LocalDate.now()
                    .plusDays(14)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
                excludeAllDayEvents = !config.allDayEvents,
            ).collectLatest { events ->
                upcomingEvents = events
                    .sortedBy { it.startTime ?: it.endTime }
            }

        }
    }
}
