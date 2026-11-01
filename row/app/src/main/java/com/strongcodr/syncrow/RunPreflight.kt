package com.strongcodr.syncrow

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.Fragment

/**
 * Checks a run must pass before the recording service is started. Shared by both Start
 * Interval buttons (Home and Live Row).
 *
 * Precise location permission and an enabled GPS provider are required: without them the run
 * does not start. The user gets one short dialog naming what is missing, with a button to the
 * place that fixes it, and presses Start again afterwards. Battery optimisation is a warning
 * only ("Start anyway" is offered); dismissing it is remembered for the current app session.
 */
object RunPreflight {

    /**
     * Requested together: on Android 12+ a request for ACCESS_FINE_LOCATION on its own is
     * ignored by the system. Requesting both shows the dialog with the Precise/Approximate
     * choice; only "Precise" grants fine location, which is what [hasPreciseLocation] checks.
     */
    val LOCATION_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** Process-scoped on purpose: a new app session warns again. */
    @Volatile
    private var batteryWarningDismissed = false

    fun hasPreciseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Location services on and the GPS provider enabled (network-only location is not enough). */
    fun isGpsEnabled(context: Context): Boolean {
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(mgr) &&
            mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Runs the checks in order and calls [proceed] when the run may start. If a check fails,
     * shows its dialog and returns without calling [proceed]; the user presses Start again.
     *
     * @param requestLocation launcher registered with [androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions];
     *   its result should go through [onLocationPermissionResult].
     */
    fun ensureReadyToStart(
        fragment: Fragment,
        requestLocation: ActivityResultLauncher<Array<String>>,
        proceed: () -> Unit
    ) {
        val context = fragment.requireContext()
        if (!hasPreciseLocation(context)) {
            showPreciseLocationDialog(fragment, requestLocation)
            return
        }
        if (!isGpsEnabled(context)) {
            showLocationOffDialog(fragment)
            return
        }
        if (!batteryWarningDismissed && !isIgnoringBatteryOptimizations(context)) {
            showBatteryDialog(fragment) {
                batteryWarningDismissed = true
                proceed()
            }
            return
        }
        proceed()
    }

    /**
     * Call from the location permission launcher's callback. Returns true when precise
     * location is granted now (the caller may continue the start flow). When the user chose
     * "Approximate" or denied for good, points them to the app settings page instead.
     */
    fun onLocationPermissionResult(fragment: Fragment): Boolean {
        if (hasPreciseLocation(fragment.requireContext())) return true
        if (!fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            showAppSettingsDialog(fragment)
        }
        return false
    }

    private fun showPreciseLocationDialog(
        fragment: Fragment,
        requestLocation: ActivityResultLauncher<Array<String>>
    ) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Precise location needed")
            .setMessage("SyncRow records GPS during a run. Allow precise location to start.")
            .setPositiveButton("Allow") { _, _ -> requestLocation.launch(LOCATION_PERMISSIONS) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppSettingsDialog(fragment: Fragment) {
        val context = fragment.requireContext()
        AlertDialog.Builder(context)
            .setTitle("Precise location is off")
            .setMessage("Turn on location for SyncRow and choose \"Use precise location\" in the app settings, then press Start again.")
            .setPositiveButton("Open settings") { _, _ ->
                openSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLocationOffDialog(fragment: Fragment) {
        val context = fragment.requireContext()
        AlertDialog.Builder(context)
            .setTitle("Turn on GPS")
            .setMessage("Location (GPS) is off. Turn it on to start a run.")
            .setPositiveButton("Open location settings") { _, _ ->
                openSettings(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatteryDialog(fragment: Fragment, onStartAnyway: () -> Unit) {
        val context = fragment.requireContext()
        AlertDialog.Builder(context)
            .setTitle("Battery optimisation is on")
            .setMessage("Android may stop the recording while the screen is off. Turn off battery optimisation for SyncRow to keep runs safe.")
            .setPositiveButton("Open settings") { _, _ ->
                openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .setNegativeButton("Start anyway") { _, _ -> onStartAnyway() }
            .show()
    }

    private fun openSettings(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds lack the specific page; the app's own settings page always exists.
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            })
        }
    }
}
