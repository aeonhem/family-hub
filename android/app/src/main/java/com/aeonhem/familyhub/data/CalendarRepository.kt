package com.aeonhem.familyhub.data

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val canWrite: Boolean,
)

/**
 * Reads and writes the Family calendar through the phone's own calendar
 * storage. Google Calendar's sync keeps it in step with everyone else, so
 * there is no sign-in or API key to set up.
 */
class CalendarRepository(private val resolver: ContentResolver) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val out = mutableListOf<CalendarInfo>()
        resolver.query(Calendars.CONTENT_URI, projection, "${Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "",
                    accountName = c.getString(2) ?: "",
                    accountType = c.getString(3) ?: "",
                    canWrite = c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return out
    }

    /** All events on [calendarId] whose local date falls in [from]..[to]. */
    fun load(calendarId: Long, from: LocalDate, to: LocalDate): List<Item> {
        // Pad by a day each side: all-day events are stored at UTC midnight,
        // which is a different local day in Brisbane.
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.DESCRIPTION,
            Instances.BEGIN,
            Instances.ALL_DAY,
        )
        val out = mutableListOf<Item>()
        resolver.query(
            uri, projection, "${Instances.CALENDAR_ID} = ?", arrayOf(calendarId.toString()),
            "${Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(4) == 1
                val instant = Instant.ofEpochMilli(c.getLong(3))
                val start = if (allDay) null else LocalDateTime.ofInstant(instant, zone)
                val date = if (allDay) instant.atZone(ZoneOffset.UTC).toLocalDate() else start!!.toLocalDate()
                if (date < from || date > to) continue
                val (kind, done, title) = Item.parseTitle(c.getString(1) ?: "")
                val (meta, note) = Item.parseDescription(c.getString(2))
                out += Item(c.getLong(0), kind, title, date, start, allDay, done, meta, note)
            }
        }
        return out
    }

    fun addDinner(cal: CalendarInfo, date: LocalDate, time: LocalTime, meal: String, cook: String?) {
        val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        insertEvent(
            cal, "${Item.DINNER} $meal", Item.buildDescription(mapOf("cook" to cook)),
            start, start + 60 * 60_000L, allDay = false,
        )
    }

    fun updateDinner(cal: CalendarInfo, item: Item, date: LocalDate, time: LocalTime, meal: String, cook: String?) {
        val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        val values = ContentValues().apply {
            put(Events.TITLE, "${Item.DINNER} $meal")
            put(Events.DESCRIPTION, Item.buildDescription(item.meta + ("cook" to cook), item.note))
            put(Events.DTSTART, start)
            put(Events.DTEND, start + 60 * 60_000L)
            put(Events.EVENT_TIMEZONE, zone.id)
        }
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, item.eventId), values, null, null)
        requestSync(cal)
    }

    fun addChore(cal: CalendarInfo, date: LocalDate, title: String, forWho: String?, from: String?) {
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        insertEvent(
            cal, "${Item.CHORE_OPEN} $title", Item.buildDescription(mapOf("for" to forWho, "from" to from)),
            start, start + 24 * 60 * 60_000L, allDay = true,
        )
    }

    fun setChoreDone(cal: CalendarInfo, item: Item, done: Boolean, by: String?) {
        val prefix = if (done) Item.CHORE_DONE else Item.CHORE_OPEN
        val meta = item.meta.toMutableMap<String, String?>().apply { put("done-by", if (done) by else null) }
        val values = ContentValues().apply {
            put(Events.TITLE, "$prefix ${item.title}")
            put(Events.DESCRIPTION, Item.buildDescription(meta, item.note))
        }
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, item.eventId), values, null, null)
        requestSync(cal)
    }

    /**
     * A memo is a short event starting a minute from now with a reminder at
     * its start, so Google Calendar pops a notification on every phone that
     * syncs the Family calendar.
     */
    fun sendMemo(cal: CalendarInfo, body: String, from: String?, to: String) {
        val start = System.currentTimeMillis() + 60_000L
        val id = insertEvent(
            cal, "${Item.MEMO} $body", Item.buildDescription(mapOf("from" to from, "for" to to)),
            start, start + 5 * 60_000L, allDay = false,
        ) ?: return
        resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id)
            put(Reminders.MINUTES, 0)
            put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })
        requestSync(cal)
    }

    private fun insertEvent(
        cal: CalendarInfo, title: String, description: String,
        start: Long, end: Long, allDay: Boolean,
    ): Long? {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, cal.id)
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, description)
            put(Events.DTSTART, start)
            put(Events.DTEND, end)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
        }
        val id = resolver.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        requestSync(cal)
        return id
    }

    /** Ask Google's sync adapter to push the change now instead of eventually. */
    private fun requestSync(cal: CalendarInfo) {
        if (cal.accountName.isBlank() || cal.accountType.isBlank()) return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        runCatching {
            ContentResolver.requestSync(Account(cal.accountName, cal.accountType), CalendarContract.AUTHORITY, extras)
        }
    }
}
