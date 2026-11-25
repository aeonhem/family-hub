package com.aeonhem.familyhub.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Content-trigger jobs don't survive a reboot, so set the memo check up again.
 * Location sharing starts again too, if it was on (and after an app update).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                MemoJobs.schedule(context)
                LocationSharing.start(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> LocationSharing.start(context)
        }
    }
}
