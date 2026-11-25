package com.aeonhem.familyhub.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A position as the family server keeps it: the last one each person shared, nothing older. */
data class Position(
    val person: String,
    val lat: Double,
    val lon: Double,
    val at: Long, // when the phone took the fix, epoch ms
    val acc: Double? = null, // metres
    val battery: Int? = null, // percent
)

class PositionList(val people: List<Position>, val now: Long)

/**
 * Where everyone is, through the family server (web/location.py). Uses the
 * parents' token. Call off the main thread.
 */
class LocationClient(private val base: String = FAMILY_SERVER) {

    fun list(token: String): PositionList = parse(JSONObject(call("GET", "/api/locations", token, null)))

    /** Sends this phone's newest fix. Returns false when the server already had a newer one. */
    fun share(token: String, p: Position): Boolean {
        val point = JSONObject().put("lat", p.lat).put("lon", p.lon).put("t", p.at)
        p.acc?.let { point.put("acc", it) }
        p.battery?.let { point.put("battery", it) }
        val body = JSONObject().put("person", p.person).put("points", JSONArray().put(point))
        return JSONObject(call("POST", "/api/location", token, body)).optBoolean("kept")
    }

    /** Stops sharing: the server deletes this person's position. */
    fun stop(token: String, person: String) {
        call("POST", "/api/location", token, JSONObject().put("person", person).put("sharing", false))
    }

    private fun call(method: String, path: String, token: String, body: JSONObject?) =
        callFamilyServer(method, path, token, body, "Location", base)

    companion object {
        fun parse(json: JSONObject): PositionList {
            val arr = json.getJSONArray("people")
            val people = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Position(
                    person = o.getString("person"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    at = o.getLong("at"),
                    acc = if (o.isNull("acc")) null else o.getDouble("acc"),
                    battery = if (o.isNull("battery")) null else o.getInt("battery"),
                )
            }
            return PositionList(people, json.optLong("now", System.currentTimeMillis()))
        }
    }
}

/**
 * How often this phone looks for its position and tells the server. The idea
 * comes from OpenBeacon's sampling policy (docs/location-sharing-plan.md), with
 * slower numbers: we keep no history, so only "where now" matters.
 */
object LocationPolicy {
    const val STILL_INTERVAL_MS = 2 * 60_000L // balanced power, about 100 m
    const val MOVING_INTERVAL_MS = 30_000L // high accuracy
    const val UPLOAD_EVERY_MS = 60_000L
    const val MOVING_SPEED_MPS = 1.0 // a brisk walk
    const val MAX_SPEED_ACCURACY_M = 50.0
    const val MOVED_M = 100.0
    const val STALE_MS = 20 * 60_000L // own position older than this: something stopped it

    /** Moving when the fix's own speed says so (if it's accurate enough), or when it is well away from the last fix. */
    fun moving(speedMps: Double?, accuracyM: Double?, metresFromLast: Double?): Boolean {
        val trusted = speedMps?.takeIf { accuracyM != null && accuracyM <= MAX_SPEED_ACCURACY_M }
        if (trusted != null) return trusted >= MOVING_SPEED_MPS
        return metresFromLast != null && metresFromLast - (accuracyM ?: 0.0) >= MOVED_M
    }

    /** Battery saver keeps the slow, low-power setting even when moving. */
    fun intervalMs(moving: Boolean, batterySaver: Boolean) =
        if (moving && !batterySaver) MOVING_INTERVAL_MS else STILL_INTERVAL_MS

    fun shouldUpload(nowMs: Long, lastUploadMs: Long?) = lastUploadMs == null || nowMs - lastUploadMs >= UPLOAD_EVERY_MS
}

/** The pause choices on the Map tab. */
enum class Pause(val label: String) {
    HOUR("1 hour"),
    TOMORROW("Until tomorrow"),
    OFF("Until I turn it back on"),
    ;

    /** When sharing starts again by itself, epoch ms; [Long.MAX_VALUE] means never. "Tomorrow" is 7 am. */
    fun until(nowMs: Long, zone: ZoneId): Long = when (this) {
        HOUR -> nowMs + 60 * 60_000L
        TOMORROW -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            .atTime(LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli()
        OFF -> Long.MAX_VALUE
    }
}

/** "just now", "5 min ago", "2 h ago", "3 days ago". */
fun ageLabel(ageMs: Long): String {
    val min = ageMs.coerceAtLeast(0) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}
