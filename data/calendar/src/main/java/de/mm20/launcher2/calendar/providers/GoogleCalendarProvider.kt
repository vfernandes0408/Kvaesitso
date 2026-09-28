package de.mm20.launcher2.calendar.providers

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import de.mm20.launcher2.search.CalendarEvent
import de.mm20.launcher2.search.calendar.CalendarListType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

internal class GoogleCalendarProvider(
    private val context: Context,
) : CalendarProvider {

    override val namespace: String = NAMESPACE

    override suspend fun search(
        query: String?,
        from: Long,
        to: Long,
        excludedCalendars: List<String>,
        excludedSources: List<String>,
        excludeAllDayEvents: Boolean,
        allowNetwork: Boolean
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (excludedSources.contains(SOURCE_ID)) return@withContext emptyList()
        if (!isAvailable(context)) return@withContext emptyList()

        val excluded = excludedCalendars.toSet()
        if (excluded.contains(SOURCE_CALENDAR_ID)) return@withContext emptyList()
        val results = mutableListOf<GoogleCalendarEvent>()

        for (account in googleAccounts()) {
            val token = getAccessToken(account) ?: continue
            val calendars = fetchCalendarList(token)

            for (calendar in calendars) {
                val calendarId = calendar.optString("id").takeIf { it.isNotBlank() } ?: continue
                val stableCalendarId = encodeCalendarId(account, calendarId)
                if (excluded.contains(stableCalendarId)) continue

                fetchEvents(
                    token = token,
                    calendarId = calendarId,
                    from = from,
                    to = to,
                    query = query,
                ).forEach { event ->
                    toEvent(
                        account = account,
                        calendar = calendar,
                        event = event,
                    )?.let { parsed ->
                        if (!excludeAllDayEvents || !parsed.allDay) {
                            results += parsed
                        }
                    }
                }
            }
        }

        results.sortedBy { it.startTime ?: it.endTime }
    }

    override suspend fun getCalendarLists(): List<CalendarList> = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext emptyList()

        listOf(
            CalendarList(
                id = "google:source",
                name = "Google Agenda",
                owner = null,
                color = 0,
                types = listOf(CalendarListType.Calendar),
                providerId = NAMESPACE,
                sourceId = SOURCE_ID,
            )
        )
    }

    fun requestAuthorization(
        activity: Activity,
        onResult: (Boolean) -> Unit,
    ) {
        val account = googleAccounts().firstOrNull()
        if (account == null) {
            onResult(false)
            return
        }

        AccountManager.get(context).getAuthToken(
            account,
            AUTH_TOKEN_TYPE,
            Bundle(),
            activity,
            { future ->
                try {
                    val token = future.result?.getString(AccountManager.KEY_AUTHTOKEN)
                    onResult(!token.isNullOrBlank())
                } catch (_: Exception) {
                    onResult(false)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun googleAccounts(): List<Account> {
        return try {
            AccountManager.get(context)
                .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
                .toList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun getAccessToken(account: Account): String? {
        return try {
            val bundle = AccountManager.get(context)
                .getAuthToken(
                    account,
                    AUTH_TOKEN_TYPE,
                    Bundle(),
                    true,
                    null,
                    null,
                )
                .result

            bundle?.getString(AccountManager.KEY_AUTHTOKEN)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCalendarList(token: String): List<JSONObject> {
        val json = request(
            token,
            "https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=250",
        ) ?: return emptyList()

        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun fetchEvents(
        token: String,
        calendarId: String,
        from: Long,
        to: Long,
        query: String?,
    ): List<JSONObject> {
        val encodedCalendarId = URLEncoder.encode(calendarId, "UTF-8")
        val timeMin = URLEncoder.encode(Instant.ofEpochMilli(from).toString(), "UTF-8")
        val timeMax = URLEncoder.encode(Instant.ofEpochMilli(to).toString(), "UTF-8")

        val url = buildString {
            append("https://www.googleapis.com/calendar/v3/calendars/")
            append(encodedCalendarId)
            append("/events?singleEvents=true&orderBy=startTime&showDeleted=true&showHiddenInvitations=false")
            append("&timeMin=").append(timeMin)
            append("&timeMax=").append(timeMax)
            append("&timeZone=").append(URLEncoder.encode(ZoneId.systemDefault().id, "UTF-8"))
            append("&maxResults=2500")
            query?.takeIf { it.isNotBlank() }?.let {
                append("&q=").append(URLEncoder.encode(it, "UTF-8"))
            }
        }

        val json = request(token, url) ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun toEvent(
        account: Account,
        calendar: JSONObject,
        event: JSONObject,
    ): GoogleCalendarEvent? {
        if (event.optString("status").equals("cancelled", ignoreCase = true)) return null

        // Google may keep a declined invitation as an event resource.
        // Only the current user's response must hide the event.
        val attendeeArray = event.optJSONArray("attendees")
        if (attendeeArray != null) {
            for (index in 0 until attendeeArray.length()) {
                val attendee = attendeeArray.optJSONObject(index) ?: continue
                if (
                    attendee.optBoolean("self", false) &&
                    attendee.optString("responseStatus").equals("declined", ignoreCase = true)
                ) {
                    return null
                }
            }
        }

        val id = event.optString("id").takeIf { it.isNotBlank() } ?: return null
        val summary = event.optString("summary").ifBlank { "(Sem título)" }
        val start = event.optJSONObject("start") ?: return null
        val end = event.optJSONObject("end") ?: return null
        val startMillis = parseDateTime(start) ?: return null
        val endMillis = parseDateTime(end) ?: return null
        val allDay = start.has("date")

        val attendees = buildList {
            val list = event.optJSONArray("attendees") ?: return@buildList
            for (index in 0 until list.length()) {
                list.optJSONObject(index)
                    ?.optString("email")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }

        return GoogleCalendarEvent(
            label = summary,
            id = id,
            color = null,
            startTime = startMillis,
            endTime = if (allDay) endMillis - 1 else endMillis,
            allDay = allDay,
            location = event.optString("location").takeIf { it.isNotBlank() },
            attendees = attendees,
            description = event.optString("description").takeIf { it.isNotBlank() },
            calendarName = calendar.optString("summary").takeIf { it.isNotBlank() },
            htmlLink = event.optString("htmlLink").takeIf { it.isNotBlank() },
            calendarId = encodeCalendarId(account, calendar.optString("id")),
        )
    }

    private fun parseDateTime(value: JSONObject): Long? {
        value.optString("dateTime").takeIf { it.isNotBlank() }?.let { valueString ->
            return try {
                OffsetDateTime.parse(valueString).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(valueString)
                        .atZone(
                            value.optString("timeZone")
                                .takeIf { it.isNotBlank() }
                                ?.let(ZoneId::of)
                                ?: ZoneId.systemDefault()
                        )
                        .toInstant()
                        .toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }

        value.optString("date").takeIf { it.isNotBlank() }?.let { date ->
            return try {
                LocalDate.parse(date)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun request(token: String, url: String): JSONObject? {
        var connection: HttpURLConnection? = null

        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode !in 200..299) return null

            BufferedReader(InputStreamReader(connection.inputStream)).use {
                JSONObject(it.readText())
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encodeCalendarId(account: Account, calendarId: String): String {
        val value = "${account.name}|$calendarId"
        return Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
    }

    companion object {
        const val NAMESPACE = "google"
        const val SOURCE_ID = "google"
        const val GOOGLE_CALENDAR_PACKAGE = GOOGLE_CALENDAR_PACKAGE
        private const val SOURCE_CALENDAR_ID = "source"

        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val AUTH_TOKEN_TYPE =
            "oauth2:https://www.googleapis.com/auth/calendar.readonly"

        fun isAvailable(context: Context): Boolean {
            return try {
                context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR_PACKAGE) != null ||
                    AccountManager.get(context)
                        .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
                        .isNotEmpty()
            } catch (_: SecurityException) {
                context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR_PACKAGE) != null
            }
        }
    }
}
