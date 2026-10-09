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
import com.aeonhem.familyhub.data.Weather
import com.aeonhem.familyhub.data.WeatherSource
import com.aeonhem.familyhub.notify.MemoJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    val weather: Weather? = null,
    val weatherFailed: Boolean = false,
)

private const val WEATHER_EVERY = 60 * 60 * 1000L // Julian asked for hourly
private const val WEATHER_CHECK = 5 * 60 * 1000L

class HubViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CalendarRepository(app.contentResolver)
    private val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        HubState(
            me = prefs.getString("me", null),
            weather = prefs.getString("weather", null)?.let { WeatherSource.fromJson(it) },
        ),
    )
    val state: StateFlow<HubState> = _state

    private var refreshJob: Job? = null
    private var pendingChange: Job? = null

    // A sync touches many rows at once; wait for it to settle, then reload once.
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            pendingChange?.cancel()
            pendingChange = viewModelScope.launch {
                delay(400)
                refresh()
            }
        }
    }
    private var observing = false

    private var weatherJob: Job? = null

    init {
        // Checks every few minutes, but only asks for new weather once it's an hour old.
        viewModelScope.launch {
            while (isActive) {
                weatherIfStale()
                delay(WEATHER_CHECK)
            }
        }
    }

    /** Also called when the app comes back on screen, in case the phone slept through a check. */
    fun weatherIfStale() {
        val age = System.currentTimeMillis() - (_state.value.weather?.fetchedAt ?: 0L)
        if (age < WEATHER_EVERY || weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { WeatherSource.fetch() } }
            result.getOrNull()?.let { prefs.edit().putString("weather", WeatherSource.toJson(it)).apply() }
            _state.update { it.copy(weather = result.getOrDefault(it.weather), weatherFailed = result.isFailure) }
        }
    }

    fun setTheme(p: Palette) {
        prefs.edit().putString("theme", p.name).apply()
        Hub.palette = p
    }

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
            val saved = cals.firstOrNull { it.id == savedId }
            // Google's Family calendar always wins, so a phone set to someone's
            // own calendar, or to another app's "Family", moves onto the shared
            // one by itself. A pick at least as good as the best stays.
            val best = cals.filter { it.familyRank > 0 && (it.canWrite || it.isGoogleFamily) }.maxByOrNull { it.familyRank }
            val chosen = when {
                saved != null && saved.familyRank >= (best?.familyRank ?: 0) -> saved
                else -> best ?: saved
            }
            _state.update { it.copy(calendars = cals.filter { c -> c.synced || c.isFamily }, calendar = chosen) }
            chosen?.let {
                prefs.edit().putLong("calendarId", it.id).apply()
                withContext(Dispatchers.IO) { repo.ensureSynced(it) }
                MemoJobs.schedule(getApplication<Application>())
            }
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
        viewModelScope.launch(Dispatchers.IO) { repo.ensureSynced(cal) }
        MemoJobs.schedule(getApplication<Application>())
        refresh()
    }

    /** Back to the calendar picker, e.g. when the wrong calendar is showing. */
    fun changeCalendar() {
        _state.update { it.copy(calendar = null) }
    }

    fun refresh() {
        val cal = _state.value.calendar ?: return
        val today = LocalDate.now()
        val weekStart = today.with(DayOfWeek.MONDAY)
        // A newer refresh replaces one still running.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
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
            s.copy(items = s.items.map { if (it.key == item.key) it.copy(done = !item.done) else it })
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
