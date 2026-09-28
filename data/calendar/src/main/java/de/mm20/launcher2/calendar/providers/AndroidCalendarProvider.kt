package de.mm20.launcher2.calendar.providers

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.core.database.getStringOrNull
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.search.CalendarEvent
import de.mm20.launcher2.search.calendar.CalendarListType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCalendarProvider(
    private val context: Context,
): CalendarProvider {
    override suspend fun search(
        query: String?,
        from: Long,
        to: Long,
        excludedCalendars: List<String>,
        excludeAllDayEvents: Boolean,
        allowNetwork: Boolean
    ): List<CalendarEvent> {
        val results = withContext(Dispatchers.IO) {
            val results = mutableListOf<AndroidCalendarEvent>()
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, from)
            ContentUris.appendId(builder, to)
            val uri = builder.build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.START_DAY,
                CalendarContract.Instances.END_DAY,
            )
            val selection = mutableListOf<String>()
            if (query != null) selection.add("${CalendarContract.Instances.TITLE} LIKE ?")
            if (excludedCalendars.isNotEmpty()) selection.add("${CalendarContract.Instances.CALENDAR_ID} NOT IN (${excludedCalendars.joinToString()})")
            if (excludeAllDayEvents) selection.add("${CalendarContract.Instances.ALL_DAY} = 0")

            // The Android Calendar Provider may keep stale rows until synchronization catches up.
            // Never expose cancelled events or events declined by the current user.
            selection.add("(${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} != 1)")
            selection.add("${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}")
            selection.add("(${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})")

            val selArgs = if (query != null) arrayOf("%$query%") else null
            val sort = "${CalendarContract.Instances.BEGIN} ASC"
            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection.joinToString(separator = " AND "),
                selArgs,
                sort
            ) ?: return@withContext mutableListOf()
            val proj = arrayOf(
                CalendarContract.Attendees.EVENT_ID,
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL
            )
            val s = "${CalendarContract.Attendees.ATTENDEE_NAME} COLLATE NOCASE ASC"
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)

                // Instances can lag behind the source Events row. Validate the
                // original event before displaying the occurrence.
                val eventSelection = """
                    ${CalendarContract.Events._ID} = ?
                    AND (${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} != 1)
                    AND ${CalendarContract.Events.STATUS} != ${CalendarContract.Events.STATUS_CANCELED}
                """.trimIndent()
                val eventCursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.STATUS,
                        CalendarContract.Events.DELETED,
                    ),
                    eventSelection,
                    arrayOf(eventId.toString()),
                    null,
                )
                val eventExists = eventCursor?.use { it.moveToFirst() } == true
                if (!eventExists) continue

                val sel = "${CalendarContract.Attendees.EVENT_ID} = $eventId"
                val cur = context.contentResolver.query(
                    CalendarContract.Attendees.CONTENT_URI,
                    proj, sel, null, s
                ) ?: return@withContext mutableListOf()
                val attendees = mutableListOf<String>()
                while (cur.moveToNext()) {
                    attendees.add(
                        cur.getStringOrNull(1).takeUnless { it.isNullOrBlank() }
                            ?: cur.getStringOrNull(2)
                            ?: continue
                    )
                }
                cur.close()
                val allday = cursor.getInt(4) > 0
                val begin = cursor.getLong(2)

                val startTime: Long
                val endTime: Long
                if (allday) {
                    val startDate = java.time.LocalDate.ofEpochDay(
                        cursor.getInt(12).toLong() - android.text.format.Time.EPOCH_JULIAN_DAY
                    )
                    val endDate = java.time.LocalDate.ofEpochDay(
                        cursor.getInt(13).toLong() - android.text.format.Time.EPOCH_JULIAN_DAY
                    )

                    startTime = startDate
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    endTime = endDate
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli() - 1L
                } else {
                    startTime = begin
                    endTime = cursor.getLong(3)
                }

                val event = AndroidCalendarEvent(
                    label = cursor.getStringOrNull(1) ?: continue,
                    id = cursor.getLong(0),
                    color = cursor.getInt(5),
                    startTime = startTime,
                    endTime = endTime,
                    allDay = allday,
                    location = cursor.getStringOrNull(6) ?: "",
                    attendees = attendees,
                    description = cursor.getStringOrNull(8)
                        ?: "",
                    calendarId = cursor.getLong(7),
                    calendarName = cursor.getStringOrNull(9)
                )
                results.add(event)
            }
            cursor.close()
            return@withContext results
        }

        return results
    }

    suspend fun get(id: Long): CalendarEvent? = withContext(Dispatchers.IO) {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, System.currentTimeMillis())
        ContentUris.appendId(builder, System.currentTimeMillis() + 63072000000L)
        val uri = builder.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val selection = """
            ${CalendarContract.Instances.EVENT_ID} = ?
            AND (${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} != 1)
            AND ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}
            AND (
                ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL
                OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}
            )
        """.trimIndent()
        val selArgs = arrayOf(id.toString())
        val cursor = context.contentResolver.query(uri, projection, selection, selArgs, null)
            ?: return@withContext null
        if (cursor.moveToNext()) {
            val title = cursor.getStringOrNull(1) ?: ""
            val begin = cursor.getLong(2)
            val end = cursor.getLong(3)
            val allday = cursor.getInt(4) != 0
            val color = cursor.getInt(5)
            val location = cursor.getStringOrNull(6)
            val calendar = cursor.getLong(7)
            val description = cursor.getStringOrNull(8)
            val calendarName = cursor.getStringOrNull(9)
            cursor.close()
            val proj = arrayOf(
                CalendarContract.Attendees.EVENT_ID,
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL
            )
            val sel = "${CalendarContract.Attendees.EVENT_ID} = $id"
            val s = "${CalendarContract.Attendees.ATTENDEE_NAME} COLLATE NOCASE ASC"
            val cur = context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                proj, sel, null, s
            ) ?: return@withContext null
            val attendees = mutableListOf<String>()
            while (cur.moveToNext()) {
                attendees.add(
                    cur.getStringOrNull(1).takeUnless { it.isNullOrBlank() }
                        ?: cur.getStringOrNull(2)
                        ?: continue
                )
            }
            cur.close()
            val startTime: Long
            val endTime: Long
            if (allday) {
                val startDate = java.time.Instant.ofEpochMilli(begin)
                    .atOffset(java.time.ZoneOffset.UTC)
                    .toLocalDate()
                val endDate = java.time.Instant.ofEpochMilli(end)
                    .atOffset(java.time.ZoneOffset.UTC)
                    .toLocalDate()

                startTime = startDate
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                endTime = endDate
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli() - 1L
            } else {
                startTime = begin
                endTime = end
            }

            return@withContext AndroidCalendarEvent(
                label = title,
                id = id,
                color = color,
                startTime = startTime,
                endTime = endTime,
                allDay = allday,
                location = location ?: "",
                attendees = attendees,
                description = description,
                calendarId = calendar,
                calendarName = calendarName
            )
        }
        cursor.close()
        return@withContext null
    }

    override suspend fun getCalendarLists(): List<CalendarList> {
        return withContext(Dispatchers.IO) {
            val calendars = mutableListOf<CalendarList>()
            val uri = CalendarContract.Calendars.CONTENT_URI
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )
            val cursor = context.contentResolver.query(uri, proj, null, null, null)
                ?: return@withContext emptyList()
            while (cursor.moveToNext()) {
                try {
                    calendars.add(
                        CalendarList(
                            id = "local:${cursor.getLong(0)}",
                            name = cursor.getStringOrNull(5) ?: cursor.getStringOrNull(1) ?: "",
                            owner = cursor.getStringOrNull(2),
                            color = cursor.getInt(3),
                            types = listOf(CalendarListType.Calendar),
                            providerId = "local",
                        )
                    )
                } catch (e: NullPointerException) {
                    continue
                }
            }
            cursor.close()
            calendars.sortBy { it.owner }
            return@withContext calendars
        }
    }

    override val namespace: String = "local"

}