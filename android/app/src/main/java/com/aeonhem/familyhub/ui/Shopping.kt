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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeonhem.familyhub.data.NeedsPasscode
import com.aeonhem.familyhub.data.ShoppingClient
import com.aeonhem.familyhub.data.ShoppingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The family's Bring! shopping list, for Julian and Sally. The family server
// signs in to Bring!; this phone only needs the parents' passcode, like the lights.

data class ShoppingState(
    val linked: Boolean = false,
    val loading: Boolean = false,
    val list: String = "",
    val items: List<ShoppingItem> = emptyList(),
    val recent: List<ShoppingItem> = emptyList(),
    val error: String? = null,
)

class ShoppingViewModel(app: Application) : AndroidViewModel(app) {
    private val client = ShoppingClient()
    private val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
    private val token: String? get() = prefs.getString("schoolToken", null)

    private val _state = MutableStateFlow(ShoppingState(linked = token != null))
    val state: StateFlow<ShoppingState> = _state

    fun refresh() {
        val t = token ?: return _state.update { it.copy(linked = false) }
        viewModelScope.launch {
            _state.update { it.copy(linked = true, loading = true) }
            val result = withContext(Dispatchers.IO) { runCatching { client.list(t) } }
            result.onSuccess { l ->
                _state.update { it.copy(loading = false, list = l.name, items = l.items, recent = l.recent, error = null) }
            }.onFailure { failed(it) }
        }
    }

    fun link(passcode: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            linkParents(getApplication(), passcode).onSuccess { refresh() }.onFailure { failed(it) }
        }
    }

    /** Shows the new item straight away (with no id until the list is read back from Bring!). */
    fun add(name: String, note: String = "") {
        if (name.isBlank()) return
        _state.update { it.copy(items = it.items + ShoppingItem("", name.trim(), note.trim())) }
        send { client.add(it, name.trim(), note.trim()) }
    }

    fun done(item: ShoppingItem) {
        if (item.id.isEmpty()) return
        _state.update { s -> s.copy(items = s.items.filter { it.id != item.id }) }
        send { client.done(it, item.id) }
    }

    private fun send(call: (String) -> Unit) {
        val t = token ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { call(t) } }
            result.onFailure { failed(it) }
            refresh()
        }
    }

    private fun failed(e: Throwable) {
        if (e is NeedsPasscode) prefs.edit().remove("schoolToken").apply()
        _state.update { it.copy(loading = false, linked = token != null, error = e.message ?: "Couldn't reach Bring!") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShoppingScreen(vm: ShoppingViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Fresh every time the app comes to the front, since the list also changes in the Bring! app.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!s.linked) {
            LinkParents("Enter the parents' passcode once to see the shopping list here.", "Show the list",
                s.error, s.loading, vm::link)
            return@Column
        }
        s.error?.let { Text(it, color = Hub.MemoInk, fontSize = 13.sp) }

        var name by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        val add = { vm.add(name, note); name = ""; note = "" }
        HubCard {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(60) },
                label = { Text("Add an item") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) add() }),
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(80) },
                label = { Text("Note, e.g. 2 litres (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) add() }),
            )
            Button(
                onClick = add, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Hub.Teal),
            ) { Text(if (s.list.isBlank()) "Add" else "Add to ${s.list}", fontWeight = FontWeight.Bold) }
        }

        HubCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.list.ifBlank { "Shopping list" }, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Hub.Ink)
                Text("${s.items.size} to buy", fontSize = 13.sp, color = Hub.Muted)
            }
            when {
                s.items.isNotEmpty() -> s.items.forEach { item -> key(item.id.ifEmpty { item.name }) { ShoppingRow(item) { vm.done(item) } } }
                s.loading -> Text("Opening the shopping list…", color = Hub.Muted, fontSize = 14.sp)
                else -> Text("Nothing to buy. Nice.", color = Hub.Muted, fontSize = 14.sp)
            }
        }

        val onList = s.items.map { it.name.lowercase() }.toSet()
        val recent = s.recent.filter { it.name.lowercase() !in onList }
        if (recent.isNotEmpty()) {
            HubCard {
                Text("Bought recently", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Hub.Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { r -> Pill(r.name, selected = false) { vm.add(r.name, r.note) } }
                }
            }
        }
        TextButton(onClick = { vm.refresh() }, enabled = !s.loading) { Text(if (s.loading) "Checking…" else "Refresh") }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, onDone: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Checkbox, onClick = onDone)
            .semantics { contentDescription = "Got ${item.name}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).background(Hub.Card, RoundedCornerShape(9.dp)).border(2.dp, Hub.Line, RoundedCornerShape(9.dp)))
        Column {
            Text(item.name, fontSize = 15.sp, color = Hub.Ink)
            if (item.note.isNotBlank()) Text(item.note, fontSize = 12.sp, color = Hub.Muted)
        }
    }
}
