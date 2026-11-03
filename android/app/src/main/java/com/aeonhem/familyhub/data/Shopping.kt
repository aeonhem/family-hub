package com.aeonhem.familyhub.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** An item on the family's Bring! list. [note] is Bring!'s "specification", e.g. "2 litres". */
data class ShoppingItem(val id: String, val name: String, val note: String = "")

/** The list's name, what's to buy, and what was bought recently (for a one tap re-add). */
class ShoppingList(val name: String, val items: List<ShoppingItem>, val recent: List<ShoppingItem>)

/** The Bring! shopping list, through the family server. Uses the parents' token. Call off the main thread. */
class ShoppingClient(private val base: String = FAMILY_SERVER) {

    fun list(token: String): ShoppingList = parse(JSONObject(call("GET", "/api/shopping", token, null)))

    fun add(token: String, name: String, note: String = "") {
        call("POST", "/api/shopping", token, JSONObject().put("name", name).put("note", note))
    }

    fun done(token: String, id: String) {
        call("POST", "/api/shopping/${URLEncoder.encode(id, "UTF-8")}/done", token, JSONObject())
    }

    private fun call(method: String, path: String, token: String?, body: JSONObject?) =
        callFamilyServer(method, path, token, body, "Shopping list", base)

    companion object {
        fun parse(json: JSONObject) = ShoppingList(
            json.optString("list", "Shopping"),
            items(json.optJSONArray("items")),
            items(json.optJSONArray("recent")),
        )

        private fun items(arr: JSONArray?) = (0 until (arr?.length() ?: 0)).map { i ->
            arr!!.getJSONObject(i).let { ShoppingItem(it.getString("id"), it.getString("name"), it.optString("note", "")) }
        }
    }
}
