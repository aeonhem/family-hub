package com.aeonhem.familyhub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Mirrors web/tests/test_server.py so the app and web page agree.
class WeatherTest {

    // A real Open-Meteo reply for Molendinar, trimmed.
    private val sample = """
        {"current":{"time":"2026-10-09T14:45","interval":900,"temperature_2m":24.9,"apparent_temperature":24.1,
        "weather_code":0,"is_day":1},"daily":{"time":["2026-10-09"],"temperature_2m_max":[25.5],
        "temperature_2m_min":[18.1],"precipitation_probability_max":[59]}}
    """.trimIndent()

    @Test
    fun parsesOpenMeteo() {
        val w = WeatherSource.parse(sample, now = 42L)
        assertEquals(24.9, w.temp, 0.001)
        assertEquals(24.1, w.feelsLike, 0.001)
        assertEquals(25.5, w.high, 0.001)
        assertEquals(18.1, w.low, 0.001)
        assertEquals(59, w.rainChance)
        assertEquals("Clear", w.label)
        assertEquals("☀️", w.icon)
        assertEquals(42L, w.fetchedAt)
    }

    @Test
    fun missingRainChance() {
        val w = WeatherSource.parse(sample.replace("[59]", "[null]"), now = 0L)
        assertNull(w.rainChance)
    }

    @Test
    fun savesAndLoads() {
        val w = WeatherSource.parse(sample, now = 42L)
        assertEquals(w, WeatherSource.fromJson(WeatherSource.toJson(w)))
        assertEquals(w.copy(rainChance = null), WeatherSource.fromJson(WeatherSource.toJson(w.copy(rainChance = null))))
        assertNull(WeatherSource.fromJson("not json"))
    }

    @Test
    fun codes() {
        assertEquals("Partly cloudy", weatherLabel(2))
        assertEquals("Showers", weatherLabel(80))
        assertEquals("Thunderstorms", weatherLabel(95))
        assertEquals("🌙", weatherIcon(0, isDay = false))
        assertEquals("🌧️", weatherIcon(63, isDay = true))
    }
}
