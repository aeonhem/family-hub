package com.aeonhem.familyhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val heading: String, val icon: ImageVector) {
    TODAY("Today", "Today", Icons.Outlined.Today),
    DINNER("Dinner", "Dinner this week", Icons.Outlined.Restaurant),
    CHORES("Chores", "Chores", Icons.Outlined.TaskAlt),
    MEMOS("Memos", "Memos", Icons.Outlined.Campaign),
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

@Composable
private fun MainScaffold(vm: HubViewModel, s: HubState) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.error) {
        s.error?.let { snackbar.showSnackbar(it); vm.clearError() }
    }
    Scaffold(
        containerColor = Hub.Ground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Hub.Card) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Hub.Teal,
                            selectedTextColor = Hub.Teal,
                            indicatorColor = Hub.TealSoft,
                            unselectedIconColor = Hub.Muted,
                            unselectedTextColor = Hub.Muted,
                        ),
                    )
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
                Column(Modifier.padding(start = 4.dp, top = 24.dp, bottom = 2.dp)) {
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
            }
            when (tab) {
                Tab.TODAY -> item { TodayScreen(s, vm) }
                Tab.DINNER -> item { DinnerScreen(s, vm) }
                Tab.CHORES -> item { ChoresScreen(s, vm) }
                Tab.MEMOS -> item { MemosScreen(s, vm) }
            }
        }
    }
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
