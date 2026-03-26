package com.strongcodr.syncrow

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.strongcodr.syncrow.storage.SensorsStore

class BleScanner(private val context: Context) {
    private val tag = "SYNCROW"

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

    private var isScanning = false

    private var onDeviceFound: ((DiscoveredDevice) -> Unit)? = null
    private var discoveredTotal = 0
    private var acceptedTotal = 0
    private var rejectedTotal = 0
    private var savedSensorMacs: Set<String> = emptySet()

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startScan(callback: (DiscoveredDevice) -> Unit) {
        if (isScanning) {
            if (DEBUG_BLE) Log.d(tag, "BLE scan start ignored: already scanning")
            return
        }
        if (bluetoothAdapter == null) {
            if (DEBUG_BLE) Log.e(tag, "BLE scan start failed: bluetoothAdapter=null sdk=${Build.VERSION.SDK_INT}")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            if (DEBUG_BLE) Log.e(tag, "BLE scan start failed: bluetooth disabled sdk=${Build.VERSION.SDK_INT}")
            return
        }
        if (!hasScanPermission()) {
            if (DEBUG_BLE) Log.e(tag, "BLE scan start failed: missing scan permission sdk=${Build.VERSION.SDK_INT}")
            return
        }

        discoveredTotal = 0
        acceptedTotal = 0
        rejectedTotal = 0
        savedSensorMacs = SensorsStore.load(context)
            .map { it.mac.lowercase() }
            .toSet()
        onDeviceFound = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        if (DEBUG_BLE) {
            Log.d(
                tag,
                "BLE scan start: mode=LOW_LATENCY sdk=${Build.VERSION.SDK_INT} acceptAll=$DEBUG_BLE_ACCEPT_ALL"
            )
        }
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
    }

    fun stopScan() {
        if (!isScanning) {
            if (DEBUG_BLE) Log.d(tag, "BLE scan stop ignored: not scanning")
            return
        }
        scanner?.stopScan(scanCallback)
        isScanning = false
        if (DEBUG_BLE) {
            Log.d(
                tag,
                "BLE scan stopped: discovered=$discoveredTotal accepted=$acceptedTotal rejected=$rejectedTotal"
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
            if (DEBUG_BLE) Log.e(tag, "BLE scan failed: errorCode=$errorCode")
        }
    }

    private fun isWitDevice(address: String?, name: String?): Boolean {
        // Accept only devices we expect:
        //  - MAC starts with C8:3A:F8 (manufacturer prefix)
        //  - Name starts with WT (sensor naming pattern)
        val matchesMac = address?.startsWith("C8:3A:F8", ignoreCase = true) == true
        val matchesName = name?.startsWith("WT", ignoreCase = true) == true
        return matchesMac || matchesName
    }

    private fun handleResult(result: ScanResult) {
        val device = result.device
        val address = device.address ?: return

        val name = device.name ?: result.scanRecord?.deviceName
        discoveredTotal += 1
        val isSavedMac = savedSensorMacs.contains(address.lowercase())

        // 🔒 Filter: Only WIT sensors
        val shouldAccept = DEBUG_BLE_ACCEPT_ALL || isSavedMac || isWitDevice(address, name)
        if (!shouldAccept) {
            rejectedTotal += 1
            if (DEBUG_BLE && (rejectedTotal <= 20 || rejectedTotal % 25 == 0)) {
                Log.d(
                    tag,
                    "BLE device rejected by filter: mac=${bleMacForLog(address)} name=${bleNameForLog(name)} " +
                        "rejectedCount=$rejectedTotal"
                )
            }
            return
        }
        acceptedTotal += 1

        val isWit = isWitDevice(address, name)
        val manufacturer = if (isWit) "WIT Electronics" else null

        val discovered = DiscoveredDevice(
            name = name,
            address = address,
            manufacturer = manufacturer,
            isWit = isWit
        )
        if (DEBUG_BLE) {
            val reason = when {
                DEBUG_BLE_ACCEPT_ALL -> "acceptAll"
                isSavedMac -> "savedMac"
                else -> "filterMatch"
            }
            Log.d(
                tag,
                "BLE device accepted: mac=${bleMacForLog(address)} name=${bleNameForLog(name)} " +
                    "acceptedCount=$acceptedTotal reason=$reason"
            )
        }
        onDeviceFound?.invoke(discovered)
    }
}
