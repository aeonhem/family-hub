package com.aeonhem.familyhub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.aeonhem.familyhub.ui.FamilyHubApp
import com.aeonhem.familyhub.ui.HubTheme
import com.aeonhem.familyhub.ui.HubViewModel

class MainActivity : ComponentActivity() {

    private val vm: HubViewModel by viewModels()

    private val perms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result -> vm.onPermission(result.values.all { it }) }

            LaunchedEffect(Unit) {
                if (hasPerms()) vm.onPermission(true) else launcher.launch(perms)
            }

            HubTheme {
                FamilyHubApp(vm = vm, onRequestPermission = { launcher.launch(perms) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPerms()) vm.refresh()
    }

    private fun hasPerms() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
