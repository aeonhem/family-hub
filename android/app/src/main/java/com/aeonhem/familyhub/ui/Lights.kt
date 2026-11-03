package com.aeonhem.familyhub.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeonhem.familyhub.data.Light
import com.aeonhem.familyhub.data.LightScene
import com.aeonhem.familyhub.data.LightsClient
import com.aeonhem.familyhub.data.NeedsPasscode
import com.aeonhem.familyhub.data.SchoolMailClient
import com.aeonhem.familyhub.notify.MemoPush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// The Smart Life lights, for Julian and Sally. The family server talks to
// Tuya's cloud; this phone only needs the parents' passcode (the same token
// as the school emails card).

data class LightsState(
    val linked: Boolean = false,
    val loading: Boolean = false,
    val lights: List<Light> = emptyList(),
    val scenes: List<LightScene> = emptyList(),
    val error: String? = null,
)

/**
 * Swaps the parents' passcode for the server token this phone keeps (the same
 * one the school emails, lights and shopping list use) and registers for memo alerts.
 */
suspend fun linkParents(app: Application, passcode: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching { SchoolMailClient().login(passcode.trim()) }
}.map { t ->
    app.getSharedPreferences("familyhub", Context.MODE_PRIVATE).edit().putString("schoolToken", t).apply()
    MemoPush.register(app)
}

class LightsViewModel(app: Application) : AndroidViewModel(app) {
    private val client = LightsClient()
    private val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
    private val token: String? get() = prefs.getString("schoolToken", null)

    private val _state = MutableStateFlow(LightsState(linked = token != null))
    val state: StateFlow<LightsState> = _state

    fun refresh() {
        val t = token ?: return _state.update { it.copy(linked = false) }
        viewModelScope.launch {
            _state.update { it.copy(linked = true, loading = true) }
            val result = withContext(Dispatchers.IO) { runCatching { client.list(t) } }
            result.onSuccess { found ->
                _state.update { it.copy(loading = false, lights = found.lights, scenes = found.scenes, error = null) }
            }
                .onFailure { failed(it) }
        }
    }

    fun link(passcode: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            linkParents(getApplication(), passcode).onSuccess { refresh() }.onFailure { failed(it) }
        }
    }

    fun setOn(light: Light, on: Boolean) = send(light.copy(on = on)) { client.setOn(it, light.id, on) }

    fun setBrightness(light: Light, percent: Int) = send(light.copy(brightness = percent)) { client.setBrightness(it, light.id, percent) }

    fun setColour(light: Light, c: LightColour) =
        if (c.temp != null) send(light.copy(mode = "white", temp = c.temp)) { client.setTemp(it, light.id, c.temp) }
        else send(light.copy(mode = "colour", color = c.hex)) { client.setColor(it, light.id, c.hex) }

    fun setScene(light: Light, scene: LightScene) =
        send(light.copy(mode = "scene", scene = scene.id)) { client.setScene(it, light.id, scene.id) }

    /** Shows [changed] straight away; the server call follows, and a failure reloads the real state. */
    private fun send(changed: Light, call: (String) -> Unit) {
        val t = token ?: return
        _state.update { s -> s.copy(lights = s.lights.map { if (it.id == changed.id) changed else it }) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { call(t) } }
            result.onFailure { failed(it); refresh() }
        }
    }

    private fun failed(e: Throwable) {
        if (e is NeedsPasscode) prefs.edit().remove("schoolToken").apply()
        _state.update { it.copy(loading = false, linked = token != null, error = e.message ?: "Couldn't reach the lights") }
    }
}

@Composable
fun LightsScreen(vm: LightsViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Fresh every time the app comes to the front, since the lights can also change in Smart Life.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!s.linked) {
            LinkParents("Enter the parents' passcode once to control the lights from here.", "Show the lights",
                s.error, s.loading, vm::link)
            return@Column
        }
        s.error?.let { Text(it, color = Hub.MemoInk, fontSize = 13.sp) }
        if (s.lights.isEmpty() && !s.loading && s.error == null) {
            Text("No lights yet. Add them in the Smart Life app, then come back here.", color = Hub.Muted, fontSize = 14.sp)
        }
        if (s.lights.isEmpty() && s.loading) Text("Finding the lights…", color = Hub.Muted, fontSize = 14.sp)
        s.lights.forEach { light -> key(light.id) { LightCard(light, s.scenes, vm) } }
        TextButton(onClick = { vm.refresh() }, enabled = !s.loading) { Text(if (s.loading) "Checking…" else "Refresh") }
    }
}

/** One tap colours: two whites, then colours. Same list as the web app's LIGHT_COLOURS (web/static/app.js). */
data class LightColour(val label: String, val hex: String, val temp: Int? = null)

val LIGHT_COLOURS = listOf(
    LightColour("Warm white", "#ffd59e", temp = 0),
    LightColour("Daylight", "#eef3ff", temp = 100),
    LightColour("Red", "#ff0000"),
    LightColour("Orange", "#ff7a00"),
    LightColour("Yellow", "#ffd000"),
    LightColour("Green", "#00e050"),
    LightColour("Teal", "#00d0c0"),
    LightColour("Blue", "#0050ff"),
    LightColour("Purple", "#8a2be2"),
    LightColour("Pink", "#ff3fa4"),
)

private fun colorOf(hex: String) = Color(android.graphics.Color.parseColor(hex))

/** Close enough to count as picked: the bulb rounds colours a little. */
private fun near(a: String?, b: String): Boolean {
    if (a == null) return false
    val x = android.graphics.Color.parseColor(a)
    val y = android.graphics.Color.parseColor(b)
    return listOf(16, 8, 0).all { abs((x shr it and 0xff) - (y shr it and 0xff)) < 24 }
}

private fun picked(light: Light, c: LightColour) =
    if (c.temp != null) light.mode == "white" && light.temp != null && abs(light.temp - c.temp) <= 10
    else light.mode == "colour" && near(light.color, c.hex)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightCard(light: Light, scenes: List<LightScene>, vm: LightsViewModel) {
    // The slider moves under the finger; the light is only told when it's let go.
    var level by remember(light.brightness) { mutableFloatStateOf((light.brightness ?: 100).toFloat()) }
    val lit = light.on && light.online

    Column(
        Modifier.fillMaxWidth().background(Hub.Card, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (lit) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb, null,
                tint = when {
                    lit && light.mode == "colour" && light.color != null -> colorOf(light.color)
                    lit -> Hub.Orange
                    else -> Hub.Muted
                },
                modifier = Modifier.size(26.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(light.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Hub.Ink)
                Text(
                    when {
                        !light.online -> "Offline. Check it's powered and on Wi-Fi."
                        !light.on -> "Off"
                        light.mode == "scene" -> scenes.firstOrNull { it.id == light.scene }?.label ?: "Scene"
                        light.brightness != null -> "On · ${light.brightness}%"
                        else -> "On"
                    },
                    fontSize = 13.sp, color = Hub.Muted,
                )
            }
            Switch(
                checked = light.on,
                onCheckedChange = { vm.setOn(light, it) },
                enabled = light.online,
                modifier = Modifier.semantics { contentDescription = "${light.name} on or off" },
                colors = SwitchDefaults.colors(checkedTrackColor = Hub.Teal, checkedThumbColor = Hub.TealSoft),
            )
        }
        if (light.brightness != null && lit && light.mode != "scene") {
            Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = { vm.setBrightness(light, level.roundToInt()) },
                valueRange = 1f..100f,
                modifier = Modifier.semantics { contentDescription = "${light.name} brightness" },
                colors = SliderDefaults.colors(thumbColor = Hub.Teal, activeTrackColor = Hub.Teal, inactiveTrackColor = Hub.Track),
            )
        }
        val colours = LIGHT_COLOURS.filter { if (it.temp != null) light.canTemp else light.canColor }
        if (lit && colours.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                colours.forEach { c ->
                    val on = picked(light, c)
                    Box(
                        Modifier
                            .size(44.dp)
                            .then(if (on) Modifier.border(3.dp, Hub.Teal, CircleShape) else Modifier)
                            .padding(if (on) 6.dp else 3.dp)
                            .background(colorOf(c.hex), CircleShape)
                            .border(1.dp, Hub.Line, CircleShape)
                            .clickable(role = Role.RadioButton) { vm.setColour(light, c) }
                            .semantics { contentDescription = c.label; selected = on },
                    )
                }
            }
        }
        if (lit && light.canScenes && scenes.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scenes.forEach { sc ->
                    FilterChip(
                        selected = light.mode == "scene" && light.scene == sc.id,
                        onClick = { vm.setScene(light, sc) },
                        label = { Text(sc.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Hub.Teal, selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
        }
    }
}

/** Asks for the parents' passcode once, for the server features on this phone. */
@Composable
fun LinkParents(prompt: String, action: String, error: String?, loading: Boolean, onLink: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(Hub.Card, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(prompt, color = Hub.Muted, fontSize = 14.sp)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Parents' passcode") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Hub.MemoInk, fontSize = 13.sp) }
        Button(onClick = { onLink(code) }, enabled = code.isNotBlank() && !loading) { Text(action) }
    }
}
