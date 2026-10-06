package com.aeonhem.familyhub.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import com.aeonhem.familyhub.MainActivity
import com.aeonhem.familyhub.data.CalendarRepository
import com.aeonhem.familyhub.data.Item

/**
 * Schedules the memo check: a one-shot job that fires when the phone's
 * calendar changes (re-armed after every run), plus a persisted 15-minute
 * job in case the trigger is ever lost.
 */
object MemoJobs {
    const val CONTENT_JOB = 1001
    const val PERIODIC_JOB = 1002

    fun schedule(ctx: Context) {
        armContentTrigger(ctx, force = false)
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(PERIODIC_JOB) != null) return
        val job = JobInfo.Builder(PERIODIC_JOB, ComponentName(ctx, MemoJobService::class.java))
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }
    }

    /** Content-trigger jobs are one-shot and can't be persisted, so each run sets up the next. */
    fun armContentTrigger(ctx: Context, force: Boolean) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        // Re-scheduling a running job stops it, so only the job itself forces it.
        if (!force && js.getPendingJob(CONTENT_JOB) != null) return
        val job = JobInfo.Builder(CONTENT_JOB, ComponentName(ctx, MemoJobService::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.Events.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                ),
            )
            .setTriggerContentUpdateDelay(1_000L)
            .setTriggerContentMaxDelay(5_000L)
            .build()
        runCatching { js.schedule(job) }
    }
}

/**
 * Notifies this phone's person about new memos for them. Google keeps
 * calendar reminders per user, so a reminder set by the sender would only
 * ever pop on the sender's phone.
 */
class MemoJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching { checkMemos(this) }
            MemoJobs.armContentTrigger(this, force = params.jobId == MemoJobs.CONTENT_JOB)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val CHANNEL = "memos"
        private const val SEEN_KEY = "notifiedMemos"
        private const val SEEN_MAX = 300
        private val lock = Any()

        fun checkMemos(ctx: Context) = synchronized(lock) {
            val prefs = ctx.getSharedPreferences("familyhub", Context.MODE_PRIVATE)
            val me = prefs.getString("me", null) ?: return@synchronized
            val calendarId = prefs.getLong("calendarId", -1L)
            if (calendarId == -1L) return@synchronized
            if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                return@synchronized
            }

            val now = System.currentTimeMillis()
            val memos = CalendarRepository(ctx.contentResolver)
                .recentMemos(calendarId, now - 24 * 60 * 60_000L)
                .filter { it.begin <= now + 2 * 60_000L } // a hand-made memo for later waits until it's due
                .filter { isForMe(it, me) }

            val stored = prefs.getString(SEEN_KEY, null)
            val seen = stored?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
            val fresh = memos.filter { it.eventId !in seen }
            // First ever run: just remember what's already there, so nobody gets a flood.
            if (stored != null) fresh.forEach { notify(ctx, it) }
            if (stored == null || fresh.isNotEmpty()) {
                val all = (seen + fresh.map { it.eventId }).distinct().takeLast(SEEN_MAX)
                prefs.edit().putString(SEEN_KEY, all.joinToString(",")).apply()
            }
        }

        private fun isForMe(m: Item, me: String): Boolean {
            val to = m.forWho?.trim().orEmpty()
            val addressed = to.isEmpty() || to.equals(me, true) || to.equals("Everyone", true) || to.equals("all", true)
            return addressed && !m.from.orEmpty().trim().equals(me, true)
        }

        private fun notify(ctx: Context, m: Item) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Memos", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Memo from ${m.from?.takeIf { it.isNotBlank() } ?: "Someone"}")
                .setContentText(m.title)
                .setStyle(Notification.BigTextStyle().bigText(m.title))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(m.eventId.hashCode(), n)
        }
    }
}
