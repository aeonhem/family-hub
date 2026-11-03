package com.aeonhem.familyhub.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

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

    private fun call(method: String, path: String, token: String?, body: JSONObject?) =
        callFamilyServer(method, path, token, body, "School emails", base)
}
