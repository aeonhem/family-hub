package com.aeonhem.familyhub.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Weather for the house, from Open-Meteo (free, no key or account). */
data class Weather(
    val place: String,
    val temp: Double,
    val feelsLike: Double,
    val code: Int,
    val isDay: Boolean,
    val high: Double,
    val low: Double,
    val rainChance: Int?,
    val fetchedAt: Long,
) {
    val label: String get() = weatherLabel(code)
    val icon: String get() = weatherIcon(code, isDay)
}

object WeatherSource {
    // Molendinar QLD, from Julian's map link. A fixed spot, so no location permission.
    const val PLACE = "Molendinar"
    private const val LAT = -27.9744
    private const val LON = 153.359

    private val URL_TEXT = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
        "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=Australia%2FBrisbane&forecast_days=1"

    /** Blocking; call off the main thread. */
    fun fetch(now: Long = System.currentTimeMillis()): Weather {
        val conn = URL(URL_TEXT).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) error("Weather service said ${conn.responseCode}")
            return parse(conn.inputStream.bufferedReader().use { it.readText() }, now)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String, now: Long): Weather {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val rain = daily.optJSONArray("precipitation_probability_max")
        return Weather(
            place = PLACE,
            temp = current.getDouble("temperature_2m"),
            feelsLike = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            high = daily.getJSONArray("temperature_2m_max").getDouble(0),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0),
            rainChance = if (rain == null || rain.isNull(0)) null else rain.getInt(0),
            fetchedAt = now,
        )
    }

    /** Saved so the card shows straight away on the next open, even offline. */
    fun toJson(w: Weather): String = JSONObject()
        .put("temp", w.temp).put("feels", w.feelsLike).put("code", w.code).put("day", w.isDay)
        .put("high", w.high).put("low", w.low).put("rain", w.rainChance ?: -1).put("at", w.fetchedAt)
        .toString()

    fun fromJson(s: String): Weather? = runCatching {
        val o = JSONObject(s)
        Weather(
            PLACE, o.getDouble("temp"), o.getDouble("feels"), o.getInt("code"), o.getBoolean("day"),
            o.getDouble("high"), o.getDouble("low"), o.getInt("rain").takeIf { it >= 0 }, o.getLong("at"),
        )
    }.getOrNull()
}

// WMO weather codes, as Open-Meteo reports them.
fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 66 -> "Light rain"
    63 -> "Rain"
    65, 67 -> "Heavy rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    80 -> "Showers"
    81, 82 -> "Heavy showers"
    95 -> "Thunderstorms"
    96, 99 -> "Storms with hail"
    else -> "Unknown"
}

fun weatherIcon(code: Int, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1 -> if (isDay) "🌤️" else "🌙"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57, 80, 81, 82 -> "🌦️"
    61, 63, 65, 66, 67 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}
