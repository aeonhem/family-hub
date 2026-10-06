package com.aeonhem.familyhub.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Content-trigger jobs don't survive a reboot, so set the memo check up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MemoJobs.schedule(context)
    }
}
