package com.aeonhem.familyhub.ui

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aeonhem.familyhub.data.CalendarInfo
import com.aeonhem.familyhub.data.CalendarRepository
import com.aeonhem.familyhub.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

val FAMILY = listOf("Julian", "Sally", "Erlina")

data class HubState(
    val hasPermission: Boolean = false,
    val me: String? = null,
    val calendars: List<CalendarInfo> = emptyList(),
    val calendar: CalendarInfo? = null,
    val items: List<Item> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class HubViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CalendarRepository(app.contentResolver)
    private val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(HubState(me = prefs.getString("me", null)))
    val state: StateFlow<HubState> = _state

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private var observing = false

    fun onPermission(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (!granted) return
        if (!observing) {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            observing = true
        }
        viewModelScope.launch {
            val cals = withContext(Dispatchers.IO) { repo.calendars() }
            val savedId = prefs.getLong("calendarId", -1L)
            val chosen = cals.firstOrNull { it.id == savedId }
                ?: cals.filter { it.canWrite && it.name.equals("Family", ignoreCase = true) }.singleOrNull()
            _state.update { it.copy(calendars = cals, calendar = chosen) }
            chosen?.let { prefs.edit().putLong("calendarId", it.id).apply() }
            refresh()
        }
    }

    fun setMe(name: String) {
        prefs.edit().putString("me", name).apply()
        _state.update { it.copy(me = name) }
    }

    fun chooseCalendar(cal: CalendarInfo) {
        prefs.edit().putLong("calendarId", cal.id).apply()
        _state.update { it.copy(calendar = cal) }
        refresh()
    }

    fun refresh() {
        val cal = _state.value.calendar ?: return
        val today = LocalDate.now()
        val weekStart = today.with(DayOfWeek.MONDAY)
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.load(cal.id, weekStart.minusDays(14), today.plusDays(14)) }
            }
            _state.update {
                it.copy(
                    loading = false,
                    items = result.getOrDefault(it.items),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    private fun write(block: (CalendarInfo) -> Unit) {
        val cal = _state.value.calendar ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(cal) } }
            result.exceptionOrNull()?.let { e -> _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun toggleChore(item: Item) {
        // Flip it on screen straight away; the calendar write follows.
        _state.update { s ->
            s.copy(items = s.items.map { if (it.eventId == item.eventId) it.copy(done = !item.done) else it })
        }
        write { repo.setChoreDone(it, item, !item.done, _state.value.me) }
    }

    fun addChore(date: LocalDate, title: String, forWho: String?) =
        write { repo.addChore(it, date, title, forWho, _state.value.me) }

    fun saveDinner(existing: Item?, date: LocalDate, time: LocalTime, meal: String, cook: String?) = write {
        if (existing == null) repo.addDinner(it, date, time, meal, cook)
        else repo.updateDinner(it, existing, date, time, meal, cook)
    }

    fun sendMemo(body: String, to: String) = write { repo.sendMemo(it, body, _state.value.me, to) }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
