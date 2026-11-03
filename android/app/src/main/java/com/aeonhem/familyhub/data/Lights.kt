package com.aeonhem.familyhub.data

import org.json.JSONObject

/**
 * A Smart Life light or plug, as the family server reports it. [brightness] is
 * 1-100 (null if it can't dim), [color] is "#rrggbb" at full brightness, [temp]
 * is white from 0 (warm) to 100 (cool), [mode] is "white", "colour" or "scene".
 */
data class Light(
    val id: String,
    val name: String,
    val online: Boolean,
    val on: Boolean,
    val brightness: Int?,
    val mode: String? = null,
    val color: String? = null,
    val temp: Int? = null,
    val scene: String? = null,
    val canColor: Boolean = false,
    val canTemp: Boolean = false,
    val canScenes: Boolean = false,
)

/** One of the server's scenes, played by the bulb itself. */
data class LightScene(val id: String, val label: String)

class LightsList(val lights: List<Light>, val scenes: List<LightScene>)

/** The Smart Life lights, switched through the family server. Uses the parents' token. Call off the main thread. */
class LightsClient(private val base: String = FAMILY_SERVER) {

    fun list(token: String): LightsList = parse(JSONObject(call("GET", "/api/lights", token, null)))

    fun setOn(token: String, id: String, on: Boolean) = change(token, id, JSONObject().put("on", on))

    fun setBrightness(token: String, id: String, percent: Int) =
        change(token, id, JSONObject().put("brightness", percent.coerceIn(1, 100)))

    fun setColor(token: String, id: String, hex: String) = change(token, id, JSONObject().put("color", hex))

    fun setTemp(token: String, id: String, temp: Int) = change(token, id, JSONObject().put("temp", temp.coerceIn(0, 100)))

    fun setScene(token: String, id: String, scene: String) = change(token, id, JSONObject().put("scene", scene))

    private fun change(token: String, id: String, body: JSONObject) {
        call("POST", "/api/lights/$id", token, body)
    }

    private fun call(method: String, path: String, token: String?, body: JSONObject?) =
        callFamilyServer(method, path, token, body, "Lights", base)

    companion object {
        fun parse(json: JSONObject): LightsList {
            val arr = json.getJSONArray("lights")
            val lights = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val can = o.optJSONObject("can")
                Light(
                    id = o.getString("id"),
                    name = o.optString("name", "Light"),
                    online = o.optBoolean("online"),
                    on = o.optBoolean("on"),
                    brightness = o.intOrNull("brightness"),
                    mode = o.stringOrNull("mode"),
                    color = o.stringOrNull("color"),
                    temp = o.intOrNull("temp"),
                    scene = o.stringOrNull("scene"),
                    canColor = can?.optBoolean("color") ?: false,
                    canTemp = can?.optBoolean("temp") ?: false,
                    canScenes = can?.optBoolean("scenes") ?: false,
                )
            }
            val sc = json.optJSONArray("scenes")
            val scenes = (0 until (sc?.length() ?: 0)).map { i ->
                sc!!.getJSONObject(i).let { LightScene(it.getString("id"), it.getString("label")) }
            }
            return LightsList(lights, scenes)
        }

        private fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)
        private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
    }
}
