package com.aeonhem.familyhub.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LightsTest {
    @Test
    fun parsesTheServersList() {
        // The shape web/server.py sends from /api/lights.
        val json = JSONObject(
            """{"lights": [
                {"id": "d1", "name": "Bedroom lamp", "online": true, "on": true, "brightness": 60, "mode": "colour",
                 "color": "#0000ff", "temp": 46, "scene": null, "can": {"color": true, "temp": true, "scenes": true}},
                {"id": "p1", "name": "Fan", "online": false, "on": false, "brightness": null, "mode": null,
                 "color": null, "temp": null, "scene": null, "can": {"color": false, "temp": false, "scenes": false}}
            ], "scenes": [{"id": "night", "label": "Night"}, {"id": "ocean", "label": "Ocean"}]}""",
        )
        val list = LightsClient.parse(json)
        assertEquals(
            listOf(
                Light("d1", "Bedroom lamp", online = true, on = true, brightness = 60, mode = "colour", color = "#0000ff",
                    temp = 46, scene = null, canColor = true, canTemp = true, canScenes = true),
                Light("p1", "Fan", online = false, on = false, brightness = null),
            ),
            list.lights,
        )
        assertEquals(listOf(LightScene("night", "Night"), LightScene("ocean", "Ocean")), list.scenes)
    }
}
