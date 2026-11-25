package com.aeonhem.familyhub.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LocationTest {
    @Test
    fun parsesTheServersList() {
        // The shape web/server.py sends from /api/locations.
        val json = JSONObject(
            """{"people": [
                {"person": "Julian", "lat": -27.97, "lon": 153.36, "at": 1764000000000, "acc": null, "battery": null},
                {"person": "Sally", "lat": -27.9, "lon": 153.4, "at": 1764000060000, "acc": 12.5, "battery": 80}
            ], "now": 1764000120000}""",
        )
        val list = LocationClient.parse(json)
        assertEquals(
            listOf(
                Position("Julian", -27.97, 153.36, 1764000000000),
                Position("Sally", -27.9, 153.4, 1764000060000, acc = 12.5, battery = 80),
            ),
            list.people,
        )
        assertEquals(1764000120000, list.now)
    }

    @Test
    fun movingNeedsATrustedSpeedOrARealMove() {
        assertTrue(LocationPolicy.moving(speedMps = 3.0, accuracyM = 10.0, metresFromLast = null))
        assertFalse(LocationPolicy.moving(speedMps = 0.2, accuracyM = 10.0, metresFromLast = 500.0)) // speed wins when accurate
        // A poor fix's speed is ignored; then only distance counts, less the fix's error.
        assertFalse(LocationPolicy.moving(speedMps = 5.0, accuracyM = 200.0, metresFromLast = 150.0))
        assertTrue(LocationPolicy.moving(speedMps = null, accuracyM = 20.0, metresFromLast = 150.0))
        assertFalse(LocationPolicy.moving(speedMps = null, accuracyM = null, metresFromLast = null))
    }

    @Test
    fun batterySaverStaysSlow() {
        assertEquals(LocationPolicy.MOVING_INTERVAL_MS, LocationPolicy.intervalMs(moving = true, batterySaver = false))
        assertEquals(LocationPolicy.STILL_INTERVAL_MS, LocationPolicy.intervalMs(moving = true, batterySaver = true))
        assertEquals(LocationPolicy.STILL_INTERVAL_MS, LocationPolicy.intervalMs(moving = false, batterySaver = false))
    }

    @Test
    fun uploadsAtMostOnceAMinute() {
        assertTrue(LocationPolicy.shouldUpload(1_000_000, null))
        assertFalse(LocationPolicy.shouldUpload(1_000_000, 1_000_000 - 30_000))
        assertTrue(LocationPolicy.shouldUpload(1_000_000, 1_000_000 - 60_000))
    }

    @Test
    fun pauseEnds() {
        val zone = ZoneId.of("Australia/Brisbane")
        val now = ZonedDateTime.of(2026, 11, 25, 21, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(now + 3_600_000, Pause.HOUR.until(now, zone))
        assertEquals(ZonedDateTime.of(2026, 11, 26, 7, 0, 0, 0, zone).toInstant().toEpochMilli(), Pause.TOMORROW.until(now, zone))
        assertEquals(Long.MAX_VALUE, Pause.OFF.until(now, zone))
    }

    @Test
    fun ageLabels() {
        assertEquals("just now", ageLabel(20_000))
        assertEquals("just now", ageLabel(-5_000)) // phone clock a little ahead of the server
        assertEquals("5 min ago", ageLabel(5 * 60_000))
        assertEquals("3 h ago", ageLabel(3 * 3_600_000))
        assertEquals("3 days ago", ageLabel(3 * 24 * 3_600_000L))
    }
}
