package com.example.row

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

object BlePermissions {

    private const val PREFS = "syncrow_prefs"
    private const val KEY_REQUESTED = "ble_permissions_requested"

    fun requiredRuntimePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missingRuntimePermissions(context: Context): List<String> {
        return requiredRuntimePermissions().filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun markRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    fun isPermanentlyDenied(fragment: Fragment, context: Context): Boolean {
        val missing = missingRuntimePermissions(context)
        if (missing.isEmpty()) return false

        val wasRequested = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUESTED, false)
        if (!wasRequested) return false

        // If we already requested before and the system says "no rationale" for all missing perms,
        // the user likely hit "Don't ask again" (or policy denies).
        return missing.all { perm -> !fragment.shouldShowRequestPermissionRationale(perm) }
    }

    fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
