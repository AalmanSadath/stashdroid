package dev.aalman.stashdroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aalman.stashdroid.ui.MainScreen
import dev.aalman.stashdroid.ui.MainViewModel
import dev.aalman.stashdroid.ui.theme.StashdroidTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    // The job notification shows progress; without the permission the job still runs, unseen.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StashdroidTheme {
                val state = vm.state.collectAsStateWithLifecycle().value
                MainScreen(state, vm, onRequestNotifications = ::requestNotifications)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // All-files access is granted in Settings, outside the app.
        vm.refresh()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
