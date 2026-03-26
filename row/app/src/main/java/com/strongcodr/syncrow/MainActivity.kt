package com.strongcodr.syncrow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import com.strongcodr.syncrow.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {
    companion object {
        private const val DIAG_PREFS = "syncrow_prefs"
        private const val KEY_LAST_FOREGROUND_ELAPSED = "diag_last_foreground_elapsed"
        private const val KEY_LAST_BACKGROUND_ELAPSED = "diag_last_background_elapsed"
        private val processStartElapsedMs: Long = SystemClock.elapsedRealtime()
        private var resumedInThisProcess = false
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (!hasLocationPermission()) {
                showLocationSettingsPrompt()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DEBUG_BLE) {
            val coldStart = savedInstanceState == null
            Log.d(
                "SYNCROW",
                "App start: processStartElapsedMs=$processStartElapsedMs coldStart=$coldStart"
            )
        }

        Notifications.ensureChannels(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        // ✅ Make Home/Sensors/Intervals top-level.
        // ✅ LiveRow is NOT top-level so Back returns to Home when you navigate into it.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.homeFragment,
                R.id.manageSensorsFragment,
                R.id.intervalsFragment
            ),
            drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        val nowElapsed = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
        val lastForegroundElapsedMs = prefs.getLong(KEY_LAST_FOREGROUND_ELAPSED, -1L)
        val processRecreated = !resumedInThisProcess
        resumedInThisProcess = true
        prefs.edit().putLong(KEY_LAST_FOREGROUND_ELAPSED, nowElapsed).apply()
        if (DEBUG_BLE) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            val isIdle = pm?.isDeviceIdleMode ?: false
            val ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
            val standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager
                    usm?.appStandbyBucket?.toString() ?: "unknown"
                } catch (_: Exception) {
                    "unavailable"
                }
            } else {
                "n/a"
            }
            Log.d(
                "SYNCROW",
                "App resume: processStartElapsedMs=$processStartElapsedMs " +
                    "lastForegroundElapsedMs=$lastForegroundElapsedMs processRecreated=$processRecreated " +
                    "deviceIdle=$isIdle ignoringBatteryOptimizations=$ignoringBatteryOptimizations " +
                    "standbyBucket=$standbyBucket"
            )
        }
        IntervalRecordingService.notifyAppForeground(this)
    }

    override fun onPause() {
        super.onPause()
        val nowElapsed = SystemClock.elapsedRealtime()
        getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BACKGROUND_ELAPSED, nowElapsed)
            .apply()
        IntervalRecordingService.notifyAppBackground(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean = false

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) return
        showLocationPrompt()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showLocationPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Allow location")
            .setMessage(
                "SyncRow records phone location during intervals so your data includes GPS coordinates.\n\n" +
                    "Please allow location access to enable this."
            )
            .setPositiveButton("Allow") { _, _ ->
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNeutralButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showLocationSettingsPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Enable location")
            .setMessage("Location permission is off. You can enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}
