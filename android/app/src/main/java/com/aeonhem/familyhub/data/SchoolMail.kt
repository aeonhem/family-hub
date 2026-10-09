package com.aeonhem.familyhub.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime

/** The family server (next to Erlina's bot). It reads Julian's Gmail, read-only. */
const val FAMILY_SERVER = "https://34-69-120-131.sslip.io"

data class SchoolEmail(
    val id: String,
    val from: String,
    val subject: String,
    val date: LocalDate,
    val time: LocalTime,
    val summary: String,
    val body: String,
    val seen: Boolean,
    val link: String,
)

class SchoolInbox(val new: List<SchoolEmail>, val seen: List<SchoolEmail>)

/** Thrown when the parents' passcode is wrong or has been changed. */
class NeedsPasscode(message: String) : IOException(message)

/** Arcadia school emails from the family server. Call off the main thread. */
class SchoolMailClient(private val base: String = FAMILY_SERVER) {

    /** Swaps the parents' passcode for the token the phone keeps. */
    fun login(passcode: String): String =
        JSONObject(call("POST", "/api/school/login", null, JSONObject().put("passcode", passcode))).getString("token")

    fun inbox(token: String): SchoolInbox {
        val json = JSONObject(call("GET", "/api/school", token, null))
        return SchoolInbox(parse(json.getJSONArray("new")), parse(json.getJSONArray("seen")))
    }

    fun setSeen(token: String, id: String, seen: Boolean) {
        call("POST", "/api/school/$id/seen", token, JSONObject().put("seen", seen))
    }

    private fun parse(arr: JSONArray) = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SchoolEmail(
            id = o.getString("id"),
            from = o.optString("from"),
            subject = o.optString("subject"),
            date = LocalDate.parse(o.getString("day")),
            time = LocalTime.parse(o.getString("time")),
            summary = o.optString("summary"),
            body = o.optString("body"),
            seen = o.optBoolean("seen"),
            link = o.optString("link"),
        )
    }

    private fun call(method: String, path: String, token: String?, body: JSONObject?): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw NeedsPasscode("Enter the parents' passcode again.")
            if (code >= 400) {
                val msg = runCatching { JSONObject(text).getString("error") }.getOrNull()
                if (code == 403 || code == 429) throw NeedsPasscode(msg ?: "That's not the passcode.")
                throw IOException(msg ?: "School emails failed ($code)")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
