package com.aeonhem.familyhub

import android.app.Application
import com.aeonhem.familyhub.notify.MemoPush

class FamilyHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase has to be ready before a memo push can be delivered.
        MemoPush.init(this)
    }
}
