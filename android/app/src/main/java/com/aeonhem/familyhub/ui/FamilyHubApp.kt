package com.aeonhem.familyhub.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.aeonhem.familyhub.data.RecentTabs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val heading: String, val icon: ImageVector, val parentsOnly: Boolean = false) {
    TODAY("Today", "Today", Icons.Outlined.Today),
    DINNER("Dinner", "Dinner this week", Icons.Outlined.Restaurant),
    CHORES("Chores", "Chores", Icons.Outlined.TaskAlt),
    MEMOS("Memos", "Memos", Icons.Outlined.Campaign),
    SHOPPING("Shopping", "Shopping", Icons.Outlined.ShoppingCart, parentsOnly = true),
    LIGHTS("Lights", "Lights", Icons.Outlined.Lightbulb, parentsOnly = true),
    MAP("Map", "Where everyone is", Icons.Outlined.Place, parentsOnly = true),
}

@Composable
fun FamilyHubApp(vm: HubViewModel, onRequestPermission: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    when {
        !s.hasPermission -> SetupScreen(
            "Family Hub needs your calendar",
            "Dinner, chores and memos live on the Family Google Calendar, so the app needs permission to read and add events.",
        ) { Button(onClick = onRequestPermission) { Text("Allow calendar access") } }

        s.me == null -> SetupScreen("Who's using this phone?", "Memos and ticked chores will be signed with your name.") {
            FAMILY.forEach { name -> PickRow(name) { vm.setMe(name) } }
        }

        s.calendar == null -> SetupScreen(
            "Pick the Family calendar",
            when {
                s.calendars.isEmpty() -> "No calendars found. Check Google Calendar is syncing on this phone."
                s.calendars.any { it.isGoogleFamily } -> "Choose the calendar the whole family shares. Google's Family calendar is at the top."
                else -> "No Google Family calendar on this phone yet. Check this Google account is in the Google family " +
                    "(Google app > Manage your Google Account > People & sharing > Family group), then reopen the app."
            },
        ) {
            s.calendars.filter { it.canWrite || it.isGoogleFamily }.sortedByDescending { it.familyRank }.forEach { cal ->
                PickRow("${cal.name}\n${cal.accountName.ifBlank { cal.ownerAccount }}") { vm.chooseCalendar(cal) }
            }
        }

        else -> MainScaffold(vm, s)
    }
}

/** A big bottom-bar button: the bar scrolls sideways when they do not all fit. */
@Composable
private fun TabButton(t: Tab, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(16.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(22.dp))
                .background(if (selected) Hub.TealSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(t.icon, contentDescription = null, tint = if (selected) Hub.Teal else Hub.Muted, modifier = Modifier.size(30.dp)) }
        Text(
            t.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
            color = if (selected) Hub.TealText else Hub.Muted, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MainScaffold(vm: HubViewModel, s: HubState) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val parent = s.me == "Julian" || s.me == "Sally"
    val tabs = Tab.entries.filter { parent || !it.parentsOnly }
    // Each person's 3 most recent tabs go first. The order is worked out once
    // when the app opens, so the buttons do not jump while someone taps them.
    val prefs = LocalContext.current.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
    val recentKey = RecentTabs.key(s.me ?: "")
    val ordered = remember(s.me, parent) {
        val recent = prefs.getString(recentKey, null)?.split(',').orEmpty()
            .mapNotNull { n -> Tab.entries.firstOrNull { it.name == n } }
        RecentTabs.order(tabs, recent)
    }
    if (tab !in tabs) tab = Tab.TODAY
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen) SettingsDialog(vm, onDismiss = { settingsOpen = false })
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.error) {
        s.error?.let { snackbar.showSnackbar(it); vm.clearError() }
    }
    Scaffold(
        containerColor = Hub.Ground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = Hub.Card, shadowElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ordered.forEach { t ->
                        TabButton(t, selected = tab == t) {
                            tab = t
                            val recent = prefs.getString(recentKey, null)?.split(',').orEmpty()
                            prefs.edit().putString(recentKey, RecentTabs.used(recent, t.name).joinToString(",")).apply()
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(Modifier.padding(start = 4.dp, top = 24.dp, bottom = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                            color = Hub.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(tab.heading, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Hub.Ink)
                        s.calendar?.let { cal ->
                            // Shows which calendar this phone reads, and is the way to change it.
                            Text(
                                "${cal.name} · ${cal.accountName} · change",
                                modifier = Modifier.clickable { vm.changeCalendar() }.padding(top = 2.dp),
                                color = Hub.Muted, fontSize = 12.sp,
                            )
                        }
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Hub.Muted)
                    }
                }
            }
            when (tab) {
                Tab.TODAY -> item { TodayScreen(s, vm) }
                Tab.DINNER -> item { DinnerScreen(s, vm) }
                Tab.CHORES -> item { ChoresScreen(s, vm) }
                Tab.MEMOS -> item { MemosScreen(s, vm) }
                Tab.SHOPPING -> item { ShoppingScreen() }
                Tab.LIGHTS -> item { LightsScreen() }
                Tab.MAP -> item { MapScreen(s.me) }
            }
        }
    }
}

@Composable
private fun SettingsDialog(vm: HubViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Theme", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Hub.Muted)
                Palette.entries.forEach { p ->
                    val picked = Hub.palette == p
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.RadioButton) { vm.setTheme(p) }
                            .semantics { selected = picked },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // A little preview: the theme's background with its main and tick colours.
                        Box(
                            Modifier.size(36.dp).background(p.ground, CircleShape).border(1.dp, p.line, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(11.dp).background(p.teal, CircleShape))
                                Box(Modifier.size(11.dp).background(p.orange, CircleShape))
                            }
                        }
                        Text(p.label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (picked) Icon(Icons.Outlined.Check, contentDescription = null, tint = Hub.TealText)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Hub.TealText, fontWeight = FontWeight.Bold) } },
    )
}

@Composable
private fun SetupScreen(title: String, body: String, content: @Composable () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(Hub.Ground),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(48.dp)) }
        item { Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Hub.Ink) }
        item { Text(body, fontSize = 15.sp, color = Hub.Muted) }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
    }
}

@Composable
private fun PickRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .background(Hub.Card, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Hub.Ink,
    )
}
