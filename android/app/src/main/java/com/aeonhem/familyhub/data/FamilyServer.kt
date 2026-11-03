package com.aeonhem.familyhub.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The family server (next to Erlina's bot). It reads Julian's Gmail, read-only, and switches the lights. */
const val FAMILY_SERVER = "https://34-69-120-131.sslip.io"

/** Thrown when the parents' passcode is wrong or has been changed. */
class NeedsPasscode(message: String) : IOException(message)

/**
 * One call to the family server with the parents' token. Returns the reply's
 * text; a refusal comes back as [NeedsPasscode] and any other failure as an
 * IOException carrying the server's message. Call off the main thread.
 */
fun callFamilyServer(method: String, path: String, token: String?, body: JSONObject?, what: String, base: String = FAMILY_SERVER): String {
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
            val msg = runCatching { JSONObject(text).getString("error") }.getOrNull() ?: text.takeIf { it.isNotBlank() && it.length < 200 }
            if (code == 403 || code == 429) throw NeedsPasscode(msg ?: "That's not the passcode.")
            throw IOException(msg ?: "$what failed ($code)")
        }
        return text
    } finally {
        conn.disconnect()
    }
}
