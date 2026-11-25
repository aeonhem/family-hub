package com.aeonhem.familyhub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeonhem.familyhub.BuildConfig
import com.aeonhem.familyhub.data.LocationClient
import com.aeonhem.familyhub.data.LocationPolicy
import com.aeonhem.familyhub.data.NeedsPasscode
import com.aeonhem.familyhub.data.Pause
import com.aeonhem.familyhub.data.Position
import com.aeonhem.familyhub.data.ageLabel
import com.aeonhem.familyhub.notify.LocationSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.ZoneId

// Where everyone is: the last position each person shared (no history), and
// this phone's own "Share my location" switch. See docs/location-sharing-plan.md.

data class MapState(
    val linked: Boolean = false,
    val loading: Boolean = false,
    val people: List<Position> = emptyList(),
    val serverNow: Long = 0L,
    val sharing: Boolean = false,
    val pausedUntil: Long = 0L,
    val lastUpload: Long? = null,
    val background: Boolean = true,
    val batteryOk: Boolean = true,
    val error: String? = null,
)

private const val MAP_REFRESH = 60_000L

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val client = LocationClient()
    private val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
    private val token: String? get() = prefs.getString("schoolToken", null)

    private val _state = MutableStateFlow(MapState(linked = token != null))
    val state: StateFlow<MapState> = _state

    /** Re-reads this phone's own settings (they can change in Android settings while the app is away). */
    fun readSettings() {
        val ctx = getApplication<Application>()
        _state.update {
            it.copy(
                sharing = LocationSharing.enabled(ctx),
                pausedUntil = LocationSharing.pausedUntil(ctx),
                lastUpload = LocationSharing.lastUpload(ctx),
                background = LocationSharing.hasBackgroundPermission(ctx),
                batteryOk = LocationSharing.ignoresBatteryOptimisation(ctx),
            )
        }
    }

    fun refresh() {
        readSettings()
        val t = token ?: return _state.update { it.copy(linked = false) }
        viewModelScope.launch {
            _state.update { it.copy(linked = true, loading = true) }
            val result = withContext(Dispatchers.IO) { runCatching { client.list(t) } }
            result.onSuccess { list ->
                _state.update { it.copy(loading = false, people = list.people, serverNow = list.now, error = null) }
            }.onFailure { e ->
                if (e is NeedsPasscode) prefs.edit().remove("schoolToken").apply()
                _state.update { it.copy(loading = false, linked = token != null, error = e.message ?: "Couldn't reach the server") }
            }
        }
    }

    fun link(passcode: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            linkParents(getApplication(), passcode)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun turnOn() {
        LocationSharing.turnOn(getApplication())
        readSettings()
    }

    fun turnOff() {
        LocationSharing.turnOff(getApplication())
        readSettings()
        delayedRefresh()
    }

    fun pause(p: Pause) {
        LocationSharing.pause(getApplication(), p.until(System.currentTimeMillis(), ZoneId.systemDefault()))
        readSettings()
        delayedRefresh()
    }

    fun resume() {
        LocationSharing.resume(getApplication())
        readSettings()
    }

    /** The server forgets a position on its own thread; look again once it has. */
    private fun delayedRefresh() {
        viewModelScope.launch { delay(1_500); refresh() }
    }
}

@Composable
fun MapScreen(me: String?, vm: MapViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MAP_REFRESH)
            vm.refresh()
        }
    }

    // Android asks in two steps: "while using the app" first, then "all the
    // time" (on Android 11+ that second step is a page in system settings).
    val backgroundAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.readSettings() }
    val foregroundAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            vm.turnOn()
            if (Build.VERSION.SDK_INT >= 29 && !LocationSharing.hasBackgroundPermission(ctx)) {
                backgroundAsk.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
    val startSharing = {
        if (LocationSharing.hasForegroundPermission(ctx)) {
            vm.turnOn()
            if (Build.VERSION.SDK_INT >= 29 && !LocationSharing.hasBackgroundPermission(ctx)) {
                backgroundAsk.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            foregroundAsk.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!s.linked) {
            LinkParents("Enter the parents' passcode once to see the family on the map.", "Show the map",
                s.error, s.loading, vm::link)
            return@Column
        }
        s.error?.let { Text(it, color = Hub.MemoInk, fontSize = 13.sp) }
        FamilyMap(s.people)
        Text("Map © OpenStreetMap contributors", color = Hub.Muted, fontSize = 11.sp)
        if (s.people.isEmpty() && !s.loading) {
            Text("Nobody is sharing their location yet.", color = Hub.Muted, fontSize = 14.sp)
        }
        s.people.forEach { PersonRow(it, s.serverNow) }
        ShareCard(me, s, ctx, startSharing, vm)
        TextButton(onClick = { vm.refresh() }, enabled = !s.loading) { Text(if (s.loading) "Checking…" else "Refresh") }
    }
}

@Composable
private fun PersonRow(p: Position, now: Long) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Hub.Card, RoundedCornerShape(16.dp))
            // Opens the position in a maps app, for directions.
            .clickable {
                val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.person)})")
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.person, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Hub.Ink)
            Text(
                listOfNotNull(ageLabel(now - p.at), p.acc?.let { "within ${it.toInt()} m" }).joinToString(" · "),
                fontSize = 13.sp, color = Hub.Muted,
            )
        }
        p.battery?.let { Text("$it%", fontSize = 13.sp, color = if (it <= 15) Hub.MemoInk else Hub.Muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareCard(me: String?, s: MapState, ctx: Context, startSharing: () -> Unit, vm: MapViewModel) {
    val now = System.currentTimeMillis()
    val paused = s.sharing && s.pausedUntil > now
    Column(
        Modifier.fillMaxWidth().background(Hub.Card, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Share my location", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Hub.Ink)
                Text(
                    when {
                        !s.sharing -> "Off. Nobody can see where ${me ?: "you"} are."
                        paused -> "Paused. The family can't see you."
                        s.lastUpload == null -> "On. Waiting for the first position…"
                        else -> "On. Last sent ${ageLabel(now - s.lastUpload)}."
                    },
                    fontSize = 13.sp, color = Hub.Muted,
                )
            }
            Switch(
                checked = s.sharing,
                onCheckedChange = { if (it) startSharing() else vm.turnOff() },
                colors = SwitchDefaults.colors(checkedTrackColor = Hub.Teal),
            )
        }
        if (!s.sharing) return@Column
        if (!s.background) {
            Text(
                "Pick \"Allow all the time\" for Family Hub's location, or sharing stops when the app is closed.",
                fontSize = 13.sp, color = Hub.MemoInk,
            )
            Button(onClick = { openAppSettings(ctx) }) { Text("Open location settings") }
        }
        if (!s.batteryOk) {
            Text("Some phones stop apps in the background to save battery. Let Family Hub run.", fontSize = 13.sp, color = Hub.Muted)
            OutlinedButton(onClick = { askBatteryExemption(ctx) }) { Text("Allow in the background") }
        }
        if (!paused && s.lastUpload != null && now - s.lastUpload > LocationPolicy.STALE_MS) {
            Text("Nothing sent for a while. Check location is on and the phone has signal.", fontSize = 13.sp, color = Hub.MemoInk)
        }
        if (paused) {
            Button(onClick = { vm.resume() }) { Text("Share again now") }
        } else {
            Text("Pause", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Hub.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pause.entries.forEach { p -> OutlinedButton(onClick = { vm.pause(p) }) { Text(p.label) } }
            }
        }
    }
}

private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
}

@SuppressLint("BatteryLife") // Sideloaded family app; Google Play's rule about this doesn't apply.
private fun askBatteryExemption(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

/** OpenStreetMap through osmdroid, one pin per person, zoomed to fit everyone. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun FamilyMap(people: List<Position>) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp)),
        factory = { ctx ->
            Configuration.getInstance().apply {
                // OSM's tile policy asks for a real app name; tiles are cached in the app's own cache.
                userAgentValue = BuildConfig.APPLICATION_ID
                osmdroidBasePath = ctx.cacheDir.resolve("osmdroid")
                osmdroidTileCache = ctx.cacheDir.resolve("osmdroid/tiles")
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(12.0)
                // Drags move the map, not the page around it.
                setOnTouchListener { v, e ->
                    v.parent?.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_CANCEL)
                    false
                }
            }
        },
        update = { map -> showPeople(map, people) },
        onRelease = { it.onDetach() },
    )
}

private fun showPeople(map: MapView, people: List<Position>) {
    val key = people.joinToString { "${it.person}${it.at}" }
    if (map.tag == key) return
    map.tag = key
    map.overlays.removeAll { it is Marker }
    people.forEach { p ->
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat, p.lon)
            title = p.person
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        })
    }
    map.invalidate()
    val points = people.map { GeoPoint(it.lat, it.lon) }
    map.post {
        when (points.size) {
            0 -> {}
            1 -> { map.controller.setZoom(15.0); map.controller.setCenter(points[0]) }
            else -> map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.4f), false, 64)
        }
    }
}
