package com.aeonhem.familyhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeonhem.familyhub.data.Item
import com.aeonhem.familyhub.data.Kind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)
private fun LocalTime.pretty() = format(timeFmt).lowercase()
private fun dayShort(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))

private val DEFAULT_DINNER_TIME: LocalTime = LocalTime.of(18, 0)

// ---------- Today ----------

@Composable
fun TodayScreen(s: HubState, vm: HubViewModel) {
    val today = LocalDate.now()
    val dinner = s.items.firstOrNull { it.kind == Kind.DINNER && it.covers(today) }
    val chores = choresFor(s.items) { it.covers(today) || (!it.done && it.endDate < today) }
    // Anything not over yet, so a Mon-Fri camp shows every day it runs.
    val now = System.currentTimeMillis()
    val comingUp = s.items.filter {
        it.kind == Kind.EVENT && it.date <= today.plusDays(7) &&
            (if (it.allDay) it.endDate >= today else it.end > now)
    }.take(5)
    val memo = s.items.filter { it.kind == Kind.MEMO && it.date >= today.minusDays(7) }.maxByOrNull { it.start ?: it.date.atStartOfDay() }
    var editing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Hub.Teal, RoundedCornerShape(20.dp))
                .clickable { editing = true }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Restaurant, null, tint = Hub.TealSoft, modifier = Modifier.size(16.dp))
                Text(
                    "Dinner tonight" + (dinner?.start?.let { " · ${it.toLocalTime().pretty()}" } ?: ""),
                    color = Hub.TealSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            Text(dinner?.title ?: "Not planned yet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                dinner?.cook?.let { "$it is cooking" } ?: if (dinner == null) "Tap to plan it" else "Tap to edit",
                color = Hub.TealSoft, fontSize = 14.sp,
            )
        }

        HubCard {
            val done = chores.count { it.done }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Chores today", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("$done of ${chores.size} done", fontSize = 13.sp, color = Hub.Muted)
            }
            ProgressBar(if (chores.isEmpty()) 0f else done.toFloat() / chores.size)
            if (chores.isEmpty()) Text("Nothing to do today.", color = Hub.Muted, fontSize = 14.sp)
            chores.forEach { ChoreRow(it, today, onToggle = { vm.toggleChore(it) }) }
        }

        HubCard {
            Text("Coming up", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (comingUp.isEmpty()) Text("Nothing on this week.", color = Hub.Muted, fontSize = 14.sp)
            comingUp.forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val whenText = when {
                        e.date == today && !e.allDay -> e.start!!.toLocalTime().pretty()
                        e.covers(today) -> "Today"
                        else -> dayShort(e.date)
                    }
                    Text(whenText, Modifier.width(60.dp), color = Hub.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(e.title, fontSize = 15.sp)
                }
            }
        }

        if (memo != null) {
            Row(
                Modifier.fillMaxWidth().background(Hub.MemoBg, RoundedCornerShape(20.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Campaign, null, tint = Hub.MemoInk)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Latest memo · ${memoMeta(memo)}", color = Hub.MemoInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(memo.title, fontSize = 15.sp)
                }
            }
        }
    }

    if (editing) DinnerDialog(today, dinner, onDismiss = { editing = false }) { date, time, meal, cook ->
        vm.saveDinner(dinner, date, time, meal, cook); editing = false
    }
}

// ---------- Dinner ----------

@Composable
fun DinnerScreen(s: HubState, vm: HubViewModel) {
    val today = LocalDate.now()
    val week = (0L..6L).map { today.with(DayOfWeek.MONDAY).plusDays(it) }
    var editingDay by remember { mutableStateOf<LocalDate?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        week.forEach { day ->
            val dinner = s.items.firstOrNull { it.kind == Kind.DINNER && it.date == day }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Hub.Card, RoundedCornerShape(16.dp))
                    .then(if (day == today) Modifier.border(2.dp, Hub.Teal, RoundedCornerShape(16.dp)) else Modifier)
                    .clickable { editingDay = day }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayShort(day).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Hub.Muted)
                    Text(day.dayOfMonth.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(dinner?.title ?: "Not planned yet", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = if (dinner == null) Hub.Muted else Hub.Ink)
                    val detail = listOfNotNull(dinner?.cook, dinner?.start?.toLocalTime()?.pretty(), if (day == today) "tonight" else null)
                    Text(if (dinner == null) "Tap to add" else detail.joinToString(" · "), fontSize = 12.sp, color = Hub.Muted)
                }
            }
        }
    }

    editingDay?.let { day ->
        val existing = s.items.firstOrNull { it.kind == Kind.DINNER && it.date == day }
        DinnerDialog(day, existing, onDismiss = { editingDay = null }) { date, time, meal, cook ->
            vm.saveDinner(existing, date, time, meal, cook); editingDay = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DinnerDialog(
    day: LocalDate, existing: Item?, onDismiss: () -> Unit,
    onSave: (LocalDate, LocalTime, String, String?) -> Unit,
) {
    var meal by remember { mutableStateOf(existing?.title ?: "") }
    var cook by remember { mutableStateOf(existing?.cook) }
    var time by remember { mutableStateOf(existing?.start?.toLocalTime() ?: DEFAULT_DINNER_TIME) }
    val times = listOf(LocalTime.of(17, 30), LocalTime.of(18, 0), LocalTime.of(18, 30), LocalTime.of(19, 0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dinner on ${day.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH))}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(meal, { meal = it }, label = { Text("What's for dinner") }, singleLine = true)
                Text("Time", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Hub.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (times + listOf(time)).distinct().sorted().forEach { t -> Pill(t.pretty(), time == t) { time = t } }
                }
                Text("Who's cooking", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Hub.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (FAMILY + "Takeaway").forEach { n -> Pill(n, cook == n) { cook = if (cook == n) null else n } }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(day, time, meal.trim(), cook) }, enabled = meal.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Chores ----------

@Composable
fun ChoresScreen(s: HubState, vm: HubViewModel) {
    val today = LocalDate.now()
    val weekStart = today.with(DayOfWeek.MONDAY)
    val week = (0L..6L).map { weekStart.plusDays(it) }
    val weekEnd = weekStart.plusDays(6)
    val erlina = s.items.filter { it.kind == Kind.CHORE && it.forWho.equals("Erlina", ignoreCase = true) }
    // Chores Erlina ticked herself this week, whoever they were for.
    val ticked = s.items.count {
        it.kind == Kind.CHORE && it.done && it.doneBy.equals("Erlina", ignoreCase = true) &&
            it.date <= weekEnd && it.endDate >= weekStart
    }
    val chores = choresFor(s.items) { (it.date <= weekEnd && it.endDate >= weekStart) || (!it.done && it.endDate < weekStart) }
    var adding by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HubCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Erlina's week", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("$ticked ticked", fontSize = 13.sp, color = Hub.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { d ->
                    val dayChores = erlina.filter { it.date == d }
                    val (bg, fg) = when {
                        dayChores.isEmpty() || d > today -> Hub.Track to Hub.Muted
                        dayChores.all { it.done } -> Hub.Orange to Color.White
                        else -> Hub.OrangeSoft to Color(0xFF8A3E12)
                    }
                    Box(
                        Modifier.weight(1f).height(40.dp).background(bg, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(dayShort(d).take(1), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        HubCard {
            Text("All chores", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (chores.isEmpty()) Text("No chores this week yet.", color = Hub.Muted, fontSize = 14.sp)
            chores.forEach { ChoreRow(it, today, showDay = true, onToggle = { vm.toggleChore(it) }) }
        }

        DashedButton("Add a chore") { adding = true }
    }

    if (adding) ChoreDialog(today, onDismiss = { adding = false }) { date, title, who ->
        vm.addChore(date, title, who); adding = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoreDialog(today: LocalDate, onDismiss: () -> Unit, onSave: (LocalDate, String, String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var who by remember { mutableStateOf<String?>("Erlina") }
    var day by remember { mutableStateOf(today) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a chore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Chore") }, singleLine = true)
                Text("Who", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Hub.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (FAMILY + "Anyone").forEach { n -> Pill(n, who == n) { who = n } }
                }
                Text("When", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Hub.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0L..6L).map { today.plusDays(it) }.forEach { d ->
                        val label = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> "${dayShort(d)} ${d.dayOfMonth}" }
                        Pill(label, day == d) { day = d }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(day, title.trim(), who?.takeIf { it != "Anyone" }) }, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Memos ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemosScreen(s: HubState, vm: HubViewModel) {
    var body by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("Everyone") }
    val memos = s.items.filter { it.kind == Kind.MEMO }.sortedByDescending { it.start ?: it.date.atStartOfDay() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HubCard {
            OutlinedTextField(
                body, { body = it }, label = { Text("New memo") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("Everyone") + FAMILY.filter { it != s.me }).forEach { n -> Pill(n, to == n) { to = n } }
            }
            Button(
                onClick = { vm.sendMemo(body.trim(), to); body = "" },
                enabled = body.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Hub.Teal),
            ) { Text("Send to ${if (to == "Everyone") "everyone" else to}", fontWeight = FontWeight.Bold) }
        }
        if (memos.isEmpty()) Text("No memos yet.", color = Hub.Muted, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        memos.forEach { m ->
            Column(
                Modifier.fillMaxWidth().background(Hub.Card, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(memoMeta(m, withTo = true), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Hub.Muted)
                Text(m.title, fontSize = 15.sp)
            }
        }
    }
}

// ---------- Shared pieces ----------

/** Chores matching [keep], open ones first, then by day. */
private fun choresFor(items: List<Item>, keep: (Item) -> Boolean) =
    items.filter { it.kind == Kind.CHORE && keep(it) }.sortedWith(compareBy({ it.done }, { it.date }, { it.title }))

private fun memoMeta(m: Item, withTo: Boolean = false): String {
    val today = LocalDate.now()
    val whenText = when {
        m.date == today && m.start != null -> m.start.toLocalTime().pretty()
        m.date == today.minusDays(1) -> "Yesterday"
        else -> m.date.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
    }
    val who = m.from ?: "Someone"
    val toText = m.forWho?.let { if (it.equals("Everyone", true) || it.equals("all", true)) "everyone" else it } ?: "everyone"
    return if (withTo) "$who to $toText · $whenText" else "$who, $whenText"
}

@Composable
private fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Hub.Card, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(Hub.Track, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(Hub.Orange, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun ChoreRow(c: Item, today: LocalDate, showDay: Boolean = false, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = (if (c.done) "Untick " else "Tick ") + c.title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(if (c.done) Hub.Orange else Hub.Card, RoundedCornerShape(9.dp))
                .border(2.dp, if (c.done) Hub.Orange else Hub.Line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (c.done) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(
                c.title, fontSize = 15.sp,
                color = if (c.done) Color(0xFF7A8480) else Hub.Ink,
                textDecoration = if (c.done) TextDecoration.LineThrough else null,
            )
            val dayText = when {
                !c.done && c.endDate < today -> "Overdue"
                c.covers(today) -> "Today"
                else -> dayShort(c.date)
            }
            val sub = listOfNotNull(c.forWho ?: "Anyone", if (showDay || dayText == "Overdue") dayText else null,
                c.doneBy?.let { "ticked by $it" })
            Text(sub.joinToString(" · "), fontSize = 12.sp, color = if (dayText == "Overdue" && !c.done) Hub.MemoInk else Hub.Muted)
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick, shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Hub.Teal),
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(
            onClick = onClick, shape = RoundedCornerShape(18.dp),
            border = BorderStroke(2.dp, Color(0xFFA9C9C6)),
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Hub.Teal) }
    }
}

@Composable
private fun DashedButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, Hub.Line),
    ) { Text(label, color = Hub.Teal, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
