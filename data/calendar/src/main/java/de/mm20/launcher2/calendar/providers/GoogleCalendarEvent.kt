package de.mm20.launcher2.calendar.providers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import de.mm20.launcher2.ktx.tryStartActivity
import de.mm20.launcher2.search.CalendarEvent
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableDeserializer
import de.mm20.launcher2.search.SearchableSerializer
import de.mm20.launcher2.serialization.Json
import kotlinx.serialization.Serializable

internal data class GoogleCalendarEvent(
    override val label: String,
    val id: String,
    override val color: Int?,
    override val startTime: Long?,
    override val endTime: Long,
    override val allDay: Boolean,
    override val location: String?,
    override val attendees: List<String>,
    override val description: String?,
    override val calendarName: String?,
    val htmlLink: String?,
    val calendarId: String,
    override val labelOverride: String? = null,
) : CalendarEvent {

    override val domain: String = Domain

    override val key: String
        get() = "$domain://$calendarId/$id"

    override fun overrideLabel(label: String): GoogleCalendarEvent {
        return copy(labelOverride = label)
    }

    override fun launch(context: Context, options: Bundle?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(htmlLink?.toUri() ?: "https://calendar.google.com/calendar/u/0/r".toUri())
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return context.tryStartActivity(intent, options)
    }

    override fun getSerializer(): SearchableSerializer = GoogleCalendarEventSerializer()

    companion object {
        const val Domain = "google.calendar"
    }
}

@Serializable
private data class SerializedGoogleCalendarEvent(
    val label: String,
    val id: String,
    val color: Int?,
    val startTime: Long?,
    val endTime: Long,
    val allDay: Boolean,
    val location: String?,
    val attendees: List<String>,
    val description: String?,
    val calendarName: String?,
    val htmlLink: String?,
    val calendarId: String,
)

private class GoogleCalendarEventSerializer : SearchableSerializer {
    override fun serialize(searchable: SavableSearchable): String {
        searchable as GoogleCalendarEvent
        return Json.Lenient.encodeToString(
            SerializedGoogleCalendarEvent(
                label = searchable.label,
                id = searchable.id,
                color = searchable.color,
                startTime = searchable.startTime,
                endTime = searchable.endTime,
                allDay = searchable.allDay,
                location = searchable.location,
                attendees = searchable.attendees,
                description = searchable.description,
                calendarName = searchable.calendarName,
                htmlLink = searchable.htmlLink,
                calendarId = searchable.calendarId,
            )
        )
    }

    override val typePrefix: String
        get() = GoogleCalendarEvent.Domain
}

internal class GoogleCalendarEventDeserializer : SearchableDeserializer {
    override suspend fun deserialize(serialized: String): SavableSearchable? {
        return try {
            val json = Json.Lenient.decodeFromString<SerializedGoogleCalendarEvent>(serialized)
            GoogleCalendarEvent(
                label = json.label,
                id = json.id,
                color = json.color,
                startTime = json.startTime,
                endTime = json.endTime,
                allDay = json.allDay,
                location = json.location,
                attendees = json.attendees,
                description = json.description,
                calendarName = json.calendarName,
                htmlLink = json.htmlLink,
                calendarId = json.calendarId,
            )
        } catch (_: Exception) {
            null
        }
    }
}
