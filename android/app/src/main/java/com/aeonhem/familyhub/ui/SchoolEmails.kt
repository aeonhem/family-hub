package com.aeonhem.familyhub.ui

import android.app.Application
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import com.aeonhem.familyhub.data.NeedsPasscode
import com.aeonhem.familyhub.data.SchoolEmail
import com.aeonhem.familyhub.data.SchoolMailClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Arcadia school emails on the Today screen, for Julian and Sally. The family
// server reads Julian's Gmail; a swipe marks an email seen for both phones.

data class SchoolState(
    val linked: Boolean = false,
    val loading: Boolean = false,
    val new: List<SchoolEmail> = emptyList(),
    val seen: List<SchoolEmail> = emptyList(),
    val error: String? = null,
)

class SchoolViewModel(app: Application) : AndroidViewModel(app) {
    private val client = SchoolMailClient()
    private val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
    private var token: String? = prefs.getString("schoolToken", null)

    private val _state = MutableStateFlow(SchoolState(linked = token != null))
    val state: StateFlow<SchoolState> = _state

    fun refresh() {
        val t = token ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = withContext(Dispatchers.IO) { runCatching { client.inbox(t) } }
            result.onSuccess { inbox ->
                _state.update { it.copy(loading = false, new = inbox.new, seen = inbox.seen, error = null) }
            }.onFailure { failed(it) }
        }
    }

    fun link(passcode: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.login(passcode.trim()) } }
            result.onSuccess { t ->
                token = t
                prefs.edit().putString("schoolToken", t).apply()
                _state.update { it.copy(linked = true) }
                refresh()
            }.onFailure { failed(it) }
        }
    }

    fun setSeen(email: SchoolEmail, seen: Boolean) {
        val t = token ?: return
        // Move it straight away; the server write follows.
        _state.update { s ->
            val moved = email.copy(seen = seen)
            if (seen) s.copy(new = s.new - email, seen = (listOf(moved) + s.seen).sortedByDescending { it.date.atTime(it.time) })
            else s.copy(seen = s.seen - email, new = (listOf(moved) + s.new).sortedByDescending { it.date.atTime(it.time) })
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.setSeen(t, email.id, seen) } }
            result.onFailure { failed(it); refresh() }
        }
    }

    private fun failed(e: Throwable) {
        if (e is NeedsPasscode) {
            token = null
            prefs.edit().remove("schoolToken").apply()
        }
        _state.update {
            it.copy(loading = false, linked = token != null, error = e.message ?: "Couldn't load school emails")
        }
    }
}

private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val clock = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)

private fun whenText(e: SchoolEmail): String {
    val t = e.time.format(clock).lowercase()
    return when (e.date) {
        LocalDate.now() -> t
        LocalDate.now().minusDays(1) -> "Yesterday $t"
        else -> e.date.format(shortDay)
    }
}

@Composable
fun SchoolEmailsCard(vm: SchoolViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Fresh every time the app comes to the front.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxWidth().background(Hub.Card, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.School, null, tint = Hub.Teal, modifier = Modifier.size(18.dp))
                Text("Arcadia emails", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (s.linked) {
                Text(
                    when {
                        s.loading && s.new.isEmpty() -> "Checking…"
                        s.new.isEmpty() -> "All seen"
                        else -> "${s.new.size} new"
                    },
                    fontSize = 13.sp, color = Hub.Muted,
                )
            }
        }

        if (!s.linked) LinkSchool(s, vm) else SchoolLists(s, vm)
    }
}

@Composable
private fun SchoolLists(s: SchoolState, vm: SchoolViewModel) {
    var showSeen by rememberSaveable { mutableStateOf(false) }
    s.error?.let { Text(it, color = Hub.MemoInk, fontSize = 13.sp, modifier = Modifier.clickable { vm.refresh() }) }
    if (s.new.isEmpty() && !s.loading && s.error == null) {
        Text("Nothing new from school.", color = Hub.Muted, fontSize = 14.sp)
    }
    if (s.new.isNotEmpty()) Text("Swipe an email away once you've read it.", color = Hub.Muted, fontSize = 12.sp)
    s.new.forEach { e -> key(e.id) { SwipeEmail(e, onSwiped = { vm.setSeen(e, true) }) } }

    if (s.seen.isNotEmpty()) {
        Text(
            (if (showSeen) "Hide seen" else "Seen (${s.seen.size})"),
            modifier = Modifier.clickable { showSeen = !showSeen }.padding(vertical = 2.dp),
            color = Hub.Teal, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        if (showSeen) {
            s.seen.forEach { e ->
                key(e.id) {
                    EmailBody(e, faded = true) {
                        TextButton(onClick = { vm.setSeen(e, false) }) { Text("Move back to new") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeEmail(e: SchoolEmail, onSwiped: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it != SwipeToDismissBoxValue.Settled) { onSwiped(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Hub.TealSoft, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Check, null, tint = Hub.Teal)
                    Text("Seen", color = Hub.Teal, fontWeight = FontWeight.Bold)
                }
            }
        },
    ) {
        EmailBody(e, faded = false)
    }
}

@Composable
private fun EmailBody(e: SchoolEmail, faded: Boolean, actions: @Composable () -> Unit = {}) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (faded) Hub.Ground else Hub.MemoBg, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${e.from} · ${whenText(e)}", color = if (faded) Hub.Muted else Hub.MemoInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(e.subject, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Hub.Ink)
        Text(if (open) e.body else e.summary, fontSize = 14.sp, color = if (faded) Hub.Muted else Hub.Ink)
        if (open) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e.link.isNotBlank()) TextButton(onClick = { uri.openUri(e.link) }) { Text("Open in Gmail") }
                actions()
            }
        } else if (faded) {
            actions()
        }
    }
}

@Composable
private fun LinkSchool(s: SchoolState, vm: SchoolViewModel) {
    var code by remember { mutableStateOf("") }
    Text("Enter the parents' passcode once to see today's school emails here.", color = Hub.Muted, fontSize = 14.sp)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Parents' passcode") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    s.error?.let { Text(it, color = Hub.MemoInk, fontSize = 13.sp) }
    Button(onClick = { vm.link(code) }, enabled = code.isNotBlank() && !s.loading) { Text("Show school emails") }
}
