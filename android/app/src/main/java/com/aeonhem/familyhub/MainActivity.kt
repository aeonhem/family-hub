package com.aeonhem.familyhub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.aeonhem.familyhub.ui.FamilyHubApp
import com.aeonhem.familyhub.ui.Hub
import com.aeonhem.familyhub.ui.HubTheme
import com.aeonhem.familyhub.ui.HubViewModel
import com.aeonhem.familyhub.ui.Palette

class MainActivity : ComponentActivity() {

    private val vm: HubViewModel by viewModels()

    private val calendarPerms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    // Memo notifications need asking for on Android 13+. The app still works without them.
    private val notifyPerms: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("familyhub", Context.MODE_PRIVATE)
        Hub.palette = Palette.named(prefs.getString("theme", null))
        setContent {
            // Status bar and window behind the app follow the picked theme.
            val palette = Hub.palette
            SideEffect {
                window.statusBarColor = palette.ground.toArgb()
                window.setBackgroundDrawable(ColorDrawable(palette.ground.toArgb()))
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !palette.dark
            }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { vm.onPermission(granted(calendarPerms)) }

            LaunchedEffect(Unit) {
                if (granted(calendarPerms)) {
                    vm.onPermission(true)
                    // Ask once for phones that granted calendar access before memos notified.
                    if (!granted(notifyPerms) && !prefs.getBoolean("askedNotify", false)) {
                        prefs.edit().putBoolean("askedNotify", true).apply()
                        launcher.launch(notifyPerms)
                    }
                } else {
                    prefs.edit().putBoolean("askedNotify", true).apply()
                    launcher.launch(calendarPerms + notifyPerms)
                }
            }

            HubTheme {
                FamilyHubApp(vm = vm, onRequestPermission = { launcher.launch(calendarPerms + notifyPerms) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (granted(calendarPerms)) vm.refresh()
        vm.weatherIfStale()
    }

    private fun granted(perms: Array<String>) = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
