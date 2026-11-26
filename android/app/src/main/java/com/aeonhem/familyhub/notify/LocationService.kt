package com.aeonhem.familyhub.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aeonhem.familyhub.MainActivity
import com.aeonhem.familyhub.data.LocationClient
import com.aeonhem.familyhub.data.LocationPolicy
import com.aeonhem.familyhub.data.NeedsPasscode
import com.aeonhem.familyhub.data.Position
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Settings and actions for "Share my location", kept in the app's prefs so the
 * service, the boot receiver and the Map tab all agree.
 */
object LocationSharing {
    private const val ENABLED = "locationSharing"
    private const val PAUSED_UNTIL = "locationPausedUntil"
    const val LAST_UPLOAD = "locationLastUpload"
    const val PENDING = "locationPending" // newest fix not yet on the server (no signal, server down)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("familyhub", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(ENABLED, false)

    fun pausedUntil(ctx: Context): Long = prefs(ctx).getLong(PAUSED_UNTIL, 0L)

    fun paused(ctx: Context, now: Long = System.currentTimeMillis()) = pausedUntil(ctx) > now

    fun lastUpload(ctx: Context): Long? = prefs(ctx).getLong(LAST_UPLOAD, 0L).takeIf { it > 0 }

    fun hasForegroundPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time": without it Android stops the service once the app is closed. */
    fun hasBackgroundPermission(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun ignoresBatteryOptimisation(ctx: Context) =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun turnOn(ctx: Context) {
        prefs(ctx).edit().putBoolean(ENABLED, true).putLong(PAUSED_UNTIL, 0L).apply()
        start(ctx)
    }

    fun turnOff(ctx: Context) {
        prefs(ctx).edit().putBoolean(ENABLED, false).putLong(PAUSED_UNTIL, 0L).remove(PENDING).apply()
        ctx.stopService(Intent(ctx, LocationService::class.java))
        forgetOnServer(ctx)
    }

    /** Stops sharing until [until] (epoch ms). The server forgets the position, so nobody sees an old one. */
    fun pause(ctx: Context, until: Long) {
        prefs(ctx).edit().putLong(PAUSED_UNTIL, until).remove(PENDING).apply()
        forgetOnServer(ctx)
        start(ctx)
    }

    fun resume(ctx: Context) {
        prefs(ctx).edit().putLong(PAUSED_UNTIL, 0L).apply()
        start(ctx)
    }

    /** Starts (or re-reads settings in) the service, if sharing is on and allowed. Safe to call often. */
    fun start(ctx: Context) {
        if (!enabled(ctx) || !hasForegroundPermission(ctx)) return
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java)) }
            .onFailure { Log.w("FamilyHub", "Couldn't start location sharing", it) }
    }

    private fun forgetOnServer(ctx: Context) {
        val p = prefs(ctx)
        val me = p.getString("me", null) ?: return
        val token = p.getString("schoolToken", null) ?: return
        Thread { runCatching { LocationClient().stop(token, me) } }.start()
    }
}

/**
 * Shares this phone's position with the family while "Share my location" is on.
 * A foreground service (Android requires the notification) with the Fused
 * Location Provider: low power every 2 min when still, every 30 s when moving.
 */
class LocationService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs by lazy { getSharedPreferences("familyhub", Context.MODE_PRIVATE) }
    private val uploads = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recheck = Runnable { apply() }
    private var requested: Pair<Long, Int>? = null // the interval and priority asked for, null when not listening
    private var last: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!showNotice()) return START_NOT_STICKY
        apply()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(recheck)
        fused.removeLocationUpdates(callback)
        uploads.shutdown()
        super.onDestroy()
    }

    /** Listens, or waits out a pause, or stops, to match the settings. */
    private fun apply() {
        main.removeCallbacks(recheck)
        if (!LocationSharing.enabled(this) || !LocationSharing.hasForegroundPermission(this)) {
            stopSelf()
            return
        }
        val until = LocationSharing.pausedUntil(this)
        if (until > System.currentTimeMillis()) {
            stopListening()
            showNotice()
            // Long pauses are rechecked hourly; START_STICKY covers the service being killed meanwhile.
            if (until != Long.MAX_VALUE) main.postDelayed(recheck, (until - System.currentTimeMillis()).coerceIn(1_000L, 60 * 60_000L))
            return
        }
        showNotice()
        listen(LocationPolicy.intervalMs(moving = false, batterySaver = batterySaver()))
        flushPending()
    }

    private fun listen(intervalMs: Long) {
        val priority = if (LocationPolicy.highAccuracy(batterySaver())) Priority.PRIORITY_HIGH_ACCURACY
        else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        if (requested == intervalMs to priority) return
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            requested = intervalMs to priority
        } catch (e: SecurityException) {
            Log.w("FamilyHub", "Location permission was taken away", e)
            stopSelf()
        }
    }

    private fun stopListening() {
        fused.removeLocationUpdates(callback)
        requested = null
    }

    private fun onFix(loc: Location) {
        if (LocationSharing.paused(this)) return
        val moving = LocationPolicy.moving(
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            metresFromLast = last?.distanceTo(loc)?.toDouble(),
        )
        last = loc
        listen(LocationPolicy.intervalMs(moving, batterySaver()))
        val me = prefs.getString("me", null) ?: return
        val p = Position(me, loc.latitude, loc.longitude, loc.time,
            if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, battery())
        prefs.edit().putString(LocationSharing.PENDING, toJson(p)).apply()
        if (LocationPolicy.shouldUpload(System.currentTimeMillis(), LocationSharing.lastUpload(this))) flushPending()
    }

    /** Sends the newest unsent fix. Only the newest matters: the server keeps no history. */
    private fun flushPending() {
        val token = prefs.getString("schoolToken", null) ?: return
        val pending = prefs.getString(LocationSharing.PENDING, null)?.let { fromJson(it) } ?: return
        uploads.execute {
            runCatching { LocationClient().share(token, pending) }
                .onSuccess {
                    val e = prefs.edit().putLong(LocationSharing.LAST_UPLOAD, System.currentTimeMillis())
                    // A newer fix may have arrived while this one was on its way; keep that one.
                    if (prefs.getString(LocationSharing.PENDING, null)?.let { fromJson(it) }?.at == pending.at) e.remove(LocationSharing.PENDING)
                    e.apply()
                }
                .onFailure { if (it is NeedsPasscode) prefs.edit().remove("schoolToken").apply() }
        }
    }

    private fun batterySaver() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    private fun battery(): Int? =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

    /** The notice Android requires while sharing. False if Android refused to start the service. */
    private fun showNotice(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        val until = LocationSharing.pausedUntil(this)
        val text = when {
            until == Long.MAX_VALUE -> "Paused. Open Family Hub to share again."
            until > System.currentTimeMillis() ->
                "Paused until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}."
            else -> "Your family can see where you are."
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing your location")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            ServiceCompat.startForeground(this, NOTICE_ID, notice,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            true
        } catch (e: Exception) {
            // Android 12+ refuses some starts from the background, and 14+ refuses
            // a location service without "Allow all the time". The Map tab says so.
            Log.w("FamilyHub", "Android refused location sharing", e)
            stopSelf()
            false
        }
    }

    companion object {
        private const val CHANNEL = "location"
        private const val NOTICE_ID = 4201

        fun toJson(p: Position): String = JSONObject().put("person", p.person).put("lat", p.lat).put("lon", p.lon)
            .put("at", p.at).put("acc", p.acc ?: JSONObject.NULL).put("battery", p.battery ?: JSONObject.NULL).toString()

        fun fromJson(s: String): Position? = runCatching {
            val o = JSONObject(s)
            Position(o.getString("person"), o.getDouble("lat"), o.getDouble("lon"), o.getLong("at"),
                if (o.isNull("acc")) null else o.getDouble("acc"), if (o.isNull("battery")) null else o.getInt("battery"))
        }.getOrNull()
    }
}
