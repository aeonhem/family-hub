package com.aeonhem.familyhub.notify

import android.content.Context
import com.aeonhem.familyhub.BuildConfig
import com.aeonhem.familyhub.data.FAMILY_SERVER
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Memo push from the family server through Firebase Cloud Messaging. The
 * calendar check in [MemoJobService] can sleep for hours on an idle phone; a
 * high-priority push wakes it. The calendar check stays as the fallback, and
 * [MemoAlerts] makes sure a memo only buzzes once whichever gets there first.
 */
object MemoPush {
    /** Off until android/app/google-services.json is in the repo. */
    val configured: Boolean get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    fun init(ctx: Context) {
        if (!configured || FirebaseApp.getApps(ctx).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .setApplicationId(BuildConfig.FCM_APP_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .build()
        runCatching { FirebaseApp.initializeApp(ctx, options) }
    }

    /**
     * Tells the server which person this phone is for. Uses the parents'
     * passcode token (the same one as school emails), so it waits until that
     * has been entered once. Safe to call often; runs on its own thread.
     */
    fun register(ctx: Context, fcmToken: String? = null) {
        if (!configured) return
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val prefs = app.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
                val me = prefs.getString("me", null) ?: return@Thread
                val auth = prefs.getString("schoolToken", null) ?: return@Thread
                init(app)
                val token = fcmToken ?: Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
                val conn = URL("$FAMILY_SERVER/api/phone/register").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer $auth")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(JSONObject().put("person", me).put("token", token).toString().toByteArray()) }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }.start()
    }
}

/** Receives the server's memo pushes. */
class MemoPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = MemoPush.register(this, token)

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val body = d["body"] ?: return
        val begin = d["begin"]?.toLongOrNull() ?: (message.sentTime / 1000)
        MemoAlerts.show(this, d["title"] ?: "Memo", body, begin)
    }
}
