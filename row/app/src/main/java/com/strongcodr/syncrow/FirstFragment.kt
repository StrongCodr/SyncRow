package com.strongcodr.syncrow

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.strongcodr.syncrow.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var bleScanner: BleScanner

    private val sensorsViewModel: SensorsViewModel by activityViewModels()

    private val devices = mutableListOf<DiscoveredDevice>()
    private var isScanning = false

    // Set of MAC addresses the user has “selected” for connection
    private val selectedAddresses = mutableSetOf<String>()

    private var startScanAfterPermission = false

    private val blePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (_binding == null) return@registerForActivityResult

            val missing = BlePermissions.missingRuntimePermissions(requireContext())
            val granted = missing.isEmpty()
            if (!granted) {
                Toast.makeText(
                    requireContext(),
                    "Bluetooth permission is required to scan for sensors.",
                    Toast.LENGTH_LONG
                ).show()
                appendLog("Enable Bluetooth permission to start scanning.")
                startScanAfterPermission = false
            } else if (startScanAfterPermission) {
                startScanning()
            }
            startScanAfterPermission = false
            refreshScanButton()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleScanner = BleScanner(requireContext())

        // Centralize BLE permission requests here (Add Sensor screen).
        // If the user denied permanently, Android will not show the system dialog again; we'll explain that.
        maybePromptForBlePermission()

        binding.buttonScan.setOnClickListener {
            if (BlePermissions.missingRuntimePermissions(requireContext()).isNotEmpty()) {
                startScanAfterPermission = true
                requestBlePermissions()
                refreshScanButton()
                return@setOnClickListener
            }

            if (!isScanning) startScanning() else stopScanning()
        }

        binding.buttonConnect.setOnClickListener {
            onConnectClicked()
        }

        refreshScanButton()
    }

    private fun maybePromptForBlePermission() {
        if (_binding == null) return

        val missing = BlePermissions.missingRuntimePermissions(requireContext())
        if (missing.isEmpty()) return

        val permanentlyDenied = BlePermissions.isPermanentlyDenied(this, requireContext())
        val message = if (permanentlyDenied) {
            "Bluetooth permission is currently denied and Android is not showing the permission popup anymore.\n\n" +
                "To re-enable it, go to Android Settings → Apps → SyncRow → Permissions and allow Bluetooth.\n\n" +
                "If you prefer, uninstall/reinstall also resets permissions."
        } else {
            "SyncRow needs Bluetooth permission to scan for and connect to your sensors."
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Bluetooth permission")
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ ->
                // Even if permanently denied, calling request here is harmless; Android will just no-op.
                requestBlePermissions()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun startScanning() {
        startScanAfterPermission = false
        devices.clear()
        selectedAddresses.clear()
        binding.devicesContainer.removeAllViews()

        appendLog("Starting scan...")
        logBleEnvironment("scan_start")

        bleScanner.startScan { dev ->
            requireActivity().runOnUiThread {
                if (devices.none { it.address == dev.address }) {
                    if (DEBUG_BLE) {
                        Log.d(
                            "SYNCROW",
                            "Add Sensor discovered mac=${bleMacForLog(dev.address)} name=${bleNameForLog(dev.name)}"
                        )
                    }
                    devices.add(dev)
                    addDeviceRow(dev)
                }
            }
        }

        isScanning = true
        refreshScanButton()
    }

    private fun stopScanning() {
        bleScanner.stopScan()
        isScanning = false
        appendLog("Scan stopped.")
        logBleEnvironment("scan_stop")
        refreshScanButton()
    }

    private fun addDeviceRow(device: DiscoveredDevice) {
        val tv = TextView(requireContext()).apply {
            textSize = 14f
            setPadding(8, 8, 8, 8)

            updateLabel(this, device)

            setOnClickListener {
                if (isAlreadyAdded(device.address)) {
                    Toast.makeText(requireContext(), "Sensor already added.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onDeviceClicked(device, this)
            }
        }

        binding.devicesContainer.addView(tv)
    }

    private fun onDeviceClicked(device: DiscoveredDevice, view: TextView) {
        val mac = device.address
        val labelName = device.name ?: mac

        if (isAlreadyAdded(mac)) {
            appendLog("Already added: $labelName")
            return
        }

        if (selectedAddresses.contains(mac)) {
            // Unselect
            selectedAddresses.remove(mac)
            appendLog("Stopped connection for $labelName.")
        } else {
            // Select
            selectedAddresses.add(mac)
            appendLog("Started connection for $labelName. Press Connect to confirm.")
        }

        // Update the row text to reflect selection state
        updateLabel(view, device)
    }

    private fun updateLabel(view: TextView, device: DiscoveredDevice) {
        val mac = device.address
        val baseLabel = buildString {
            append(device.address)
            append(" | ")
            append(device.name ?: "Unknown")
            if (device.manufacturer != null) append(" | ${device.manufacturer}")
            if (device.isWit) append("  [WIT]")
        }

        val alreadyAdded = isAlreadyAdded(mac)

        view.isEnabled = !alreadyAdded
        view.alpha = if (alreadyAdded) 0.5f else 1.0f

        view.text = when {
            alreadyAdded -> "✓ (added) $baseLabel"
            selectedAddresses.contains(mac) -> "✓ $baseLabel"
            else -> baseLabel
        }
    }

    private fun requestBlePermissions() {
        val perms = BlePermissions.requiredRuntimePermissions()
        BlePermissions.markRequested(requireContext())
        blePermissionsLauncher.launch(perms)
    }

    private fun refreshScanButton() {
        val b = _binding ?: return
        val hasPermission = BlePermissions.missingRuntimePermissions(requireContext()).isEmpty()
        b.buttonScan.text = if (hasPermission) {
            if (isScanning) "Stop Scan" else "Start Scan"
        } else {
            "Enable Bluetooth"
        }
        b.buttonScan.isEnabled = hasPermission || !startScanAfterPermission
    }

    private fun onConnectClicked() {
        if (selectedAddresses.isEmpty()) {
            appendLog("No sensors selected. Tap a sensor in the list first.")
            return
        }

        // Register all selected sensors into SensorsViewModel
        selectedAddresses.forEach { mac ->
            if (isAlreadyAdded(mac)) {
                val name = devices.find { it.address == mac }?.name ?: mac
                appendLog("Skipped $name (already added).")
                return@forEach
            }
            val device = devices.find { it.address == mac }
            val name = device?.name ?: mac
            logBleEnvironment("pair_connect_click_${bleMacForLog(mac)}")
            sensorsViewModel.addSensor(mac = mac, name = device?.name)
            appendLog("Connected to $name.")
        }

        // After confirming, go back to Manage Sensors
        findNavController().popBackStack()
    }

    private fun isAlreadyAdded(mac: String): Boolean {
        val current = sensorsViewModel.sensors.value.orEmpty()
        return current.any { it.mac.equals(mac, ignoreCase = true) }
    }

    private fun logBleEnvironment(event: String) {
        val ctx = requireContext()
        val btAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (DEBUG_BLE) {
            Log.d(
                "SYNCROW",
                "BLE env event=$event sdk=${Build.VERSION.SDK_INT} " +
                    "btEnabled=${btAdapter?.isEnabled == true} btState=${btAdapter?.state ?: BluetoothAdapter.ERROR} " +
                    "scanPerm=$scanPerm connectPerm=$connectPerm"
            )
        }
    }

    // SAFE: does nothing if view is already destroyed
    private fun appendLog(line: String) {
        val b = _binding ?: return

        val old = b.textLog.text?.toString() ?: ""
        val newText = if (old.isEmpty()) line else "$old\n$line"
        b.textLog.text = newText

        b.scrollLog.post {
            b.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onStop() {
        super.onStop()

        // Stop scanning when leaving Add Sensor page
        if (isScanning) {
            bleScanner.stopScan()
            isScanning = false
            refreshScanButton()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
