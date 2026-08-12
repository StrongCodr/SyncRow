package com.strongcodr.syncrow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.strongcodr.syncrow.databinding.FragmentLiveRowBinding
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import android.net.Uri

class LiveRowFragment : Fragment() {

    private var _binding: FragmentLiveRowBinding? = null
    private val binding get() = _binding!!

    private val sensorsViewModel: SensorsViewModel by activityViewModels()

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStartAfterNotifications = false
    private var lastConnectOnlySignature: String? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            val sensors = sensorsViewModel.sensors.value.orEmpty()
            val running = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)
            binding.buttonInterval.text = if (running) "Stop Interval" else "Start Interval"

            var spmSum = 0
            var spmCount = 0
            sensors.forEach { sensor ->
                val views = rowViews[sensor.id] ?: return@forEach
                val status = prefs.getString(statusKey(sensor.mac), "DISCONNECTED") ?: "DISCONNECTED"
                val strokes = prefs.getInt(strokesKey(sensor.mac), 0)
                val spm = prefs.getInt(spmKey(sensor.mac), 0)
                val connected = prefs.getBoolean(connectedKey(sensor.mac), false)

                val displayStatus = displayStatus(running, connected, status)

                views.status.text = displayStatus
                views.spm.text = "SPM: $spm"
                views.strokes.text = "Strokes: $strokes"

                // Per-seat gating: name why a seat is unavailable instead of freezing
                // its last number. "sensor dropout" = held/degraded; "-- ms" = no catch yet.
                val syncStatus = prefs.getString(syncStatusKey(sensor.mac), null)
                val latenessMs = prefs.getLong(latenessKey(sensor.mac), Long.MIN_VALUE)
                views.lateness?.text = when {
                    syncStatus == "DEGRADED_SIGNAL" -> "Lateness vs Stroke: sensor dropout"
                    latenessMs == Long.MIN_VALUE -> "Lateness vs Stroke: -- ms"
                    else -> {
                        val sign = if (latenessMs > 0) "+" else ""
                        "Lateness vs Stroke: $sign${latenessMs} ms"
                    }
                }

                spmSum += spm
                spmCount += 1
            }

            val avg = if (spmCount > 0) spmSum / spmCount else 0
            binding.textLiveSummary.text = "Crew Avg SPM: $avg | Sync spread: -- | Technique consistency: --"

            // Cox connection status — separate from the rower loop above.
            coxSensorMac?.let { mac ->
                val connected = prefs.getBoolean(connectedKey(mac), false)
                val status = prefs.getString(statusKey(mac), "DISCONNECTED") ?: "DISCONNECTED"
                coxStatusView?.text = displayStatus(running, connected, status)
            }

            handler.postDelayed(this, 200L)
        }
    }

    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (areNotificationsUsable()) {
                continueStartFlow()
            } else {
                pendingStartAfterNotifications = true
                openNotificationSettings()
            }
        }

    private data class RowViews(
        val status: android.widget.TextView,
        val spm: android.widget.TextView,
        val strokes: android.widget.TextView,
        val lateness: android.widget.TextView? = null
    )

    private val rowViews = ConcurrentHashMap<Long, RowViews>()

    // Cox has no stroke/SPM/lateness — we only need to reflect connection state on its
    // card. Tracked separately so the normal per-rower RowViews loop stays homogeneous.
    private var coxStatusView: android.widget.TextView? = null
    private var coxSensorMac: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveRowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Notifications.ensureChannels(requireContext())

        sensorsViewModel.sensors.observe(viewLifecycleOwner) { sensors ->
            rebuildRows(sensors)
            if (sensors.isEmpty()) {
                lastConnectOnlySignature = null
                return@observe
            }
            ensureConnectedIfNeeded()
        }

        binding.buttonInterval.setOnClickListener { onStartStopPressed() }
    }

    override fun onStart() {
        super.onStart()
        ensureConnectedIfNeeded()
        ensureInitialStatus()
        handler.post(pollRunnable)
        if (pendingStartAfterNotifications && areNotificationsUsable()) {
            pendingStartAfterNotifications = false
            continueStartFlow()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(pollRunnable)
    }

    private fun onStartStopPressed() {
        val running = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)

        if (running) {
            prefs.edit().putBoolean(KEY_INTERVAL_RUNNING, false).apply()
            binding.buttonInterval.text = "Start Interval"
            IntervalRecordingService.stop(requireContext())
            scheduleStopWatchdog()
            return
        }

        if (!areNotificationsUsable()) {
            requestNotificationsNow()
            return
        }

        continueStartFlow()
    }

    private fun areNotificationsUsable(): Boolean {
        if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    private fun requestNotificationsNow() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                val targetSdk = requireContext().applicationInfo.targetSdkVersion
                if (targetSdk >= 33) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Allow notifications")
                        .setMessage(
                            "SyncRow requires notifications so you can see recording status and get alerts if an interval fails.\n\n" +
                                "Without notifications, intervals cannot start."
                        )
                        .setPositiveButton("Allow") { _, _ ->
                            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Not now", null)
                        .show()
                    return
                }
            }
        }

        pendingStartAfterNotifications = true
        AlertDialog.Builder(requireContext())
            .setTitle("Enable notifications")
            .setMessage("SyncRow requires notifications to run intervals reliably and report failures.")
            .setPositiveButton("Open Settings") { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun requestAddSensorsNow() {
        AlertDialog.Builder(requireContext())
            .setTitle("Add sensors first")
            .setMessage("You need to add at least one sensor before starting an interval.")
            .setPositiveButton("Go to Add Sensor") { _, _ ->
                findNavController().navigate(R.id.addSensorFragment)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBleNow() {
        AlertDialog.Builder(requireContext())
            .setTitle("Bluetooth permission needed")
            .setMessage(
                "SyncRow needs Bluetooth permission to connect to sensors.\n\n" +
                    "Please grant Bluetooth permission from the Add Sensor screen."
            )
            .setPositiveButton("Go to Add Sensor") { _, _ ->
                findNavController().navigate(R.id.addSensorFragment)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun continueStartFlow() {
        val sensors = sensorsViewModel.sensors.value.orEmpty()
        if (sensors.isEmpty()) {
            requestAddSensorsNow()
            return
        }

        if (!BlePermissions.hasConnectPermission(requireContext())) {
            requestBleNow()
            return
        }

        IntervalRecordingService.start(requireContext(), sensors)
    }

    private fun openNotificationSettings() {
        val context = requireContext()
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun rebuildRows(sensors: List<Sensor>) {
        val b = _binding ?: return
        rowViews.clear()
        coxStatusView = null
        coxSensorMac = null
        b.containerSensors.removeAllViews()
        val mode = RowingModeStore.getUiMode(requireContext())
        b.labelSensors.text = if (mode == RowingMode.SWEEP) "Mode: Sweep | Stroke reference: Seat ${sensors.size}" else "Mode: Sculling | Stroke reference: Seat ${(sensors.size + 1) / 2}"

        if (sensors.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "No sensors registered. Add one in Manage Sensors."
                textSize = 16f
            }
            b.containerSensors.addView(empty)
            return
        }

        if (mode == RowingMode.SWEEP) {
            buildSweepRows(sensors, b)
        } else {
            buildScullingRows(sensors, b)
        }
    }

    private fun buildSweepRows(sensors: List<Sensor>, b: FragmentLiveRowBinding) {
        val rowerCount = sensors.count { it.role == SensorRole.SEAT }
        var rowerIdx = 0
        sensors.forEach { sensor ->
            val isCox = sensor.role == SensorRole.COX
            val seatNumber = if (isCox) {
                0
            } else {
                val n = rowerCount - rowerIdx
                rowerIdx++
                n
            }
            val card = buildCard()
            val displayName = sensor.name?.trim()?.takeIf { it.isNotEmpty() } ?: sensor.mac
            val title = TextView(requireContext()).apply {
                text = if (isCox) "$COX_INDICATOR Cox - $displayName"
                    else "Seat $seatNumber - $displayName"
                textSize = 18f
            }
            val status = TextView(requireContext()).apply {
                text = "DISCONNECTED"
                textSize = 14f
            }
            val spm = TextView(requireContext()).apply {
                // Cox doesn't row — no strokes, no rate. Seed with the final text so we
                // never show a misleading "-- spm" that would look like a missing update.
                text = if (isCox) "Hull reference (no stroke)" else "SPM: --"
                textSize = 16f
            }
            val strokes = TextView(requireContext()).apply {
                text = if (isCox) "" else "Strokes: 0"
                textSize = 16f
                if (isCox) visibility = View.GONE
            }
            val lateness = TextView(requireContext()).apply {
                text = if (isCox) "" else "Lateness vs Stroke: -- ms"
                textSize = 13f
                if (isCox) visibility = View.GONE
            }
            val technique = TextView(requireContext()).apply {
                text = if (isCox) "" else "Technique score: --"
                textSize = 13f
                if (isCox) visibility = View.GONE
            }

            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(status)
                addView(spm)
                addView(strokes)
                addView(lateness)
                addView(technique)
            }
            card.addView(inner)
            b.containerSensors.addView(card)
            // Only track rower rows for live updates. Cox has nothing per-tick to show
            // beyond connection state, and its status TextView is wired below.
            if (!isCox) {
                rowViews[sensor.id] = RowViews(status, spm, strokes, lateness)
            } else {
                coxStatusView = status
                coxSensorMac = sensor.mac
            }
        }
    }

    private fun buildScullingRows(sensors: List<Sensor>, b: FragmentLiveRowBinding) {
        CrewLayout.toScullingSeats(sensors).forEach { seat ->
            val card = buildCard()
            val inner = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(TextView(requireContext()).apply {
                text = "Seat ${seat.seatNumber}"
                textSize = 18f
            })
            inner.addView(buildScullingSideBlock("Starboard", seat.port))
            inner.addView(buildScullingSideBlock("Port", seat.starboard))
            inner.addView(TextView(requireContext()).apply {
                text = "Seat combined SPM: --"
                textSize = 13f
                setPadding(0, 8, 0, 0)
            })
            inner.addView(TextView(requireContext()).apply {
                text = "Seat lateness vs stroke: -- ms"
                textSize = 13f
            })
            inner.addView(TextView(requireContext()).apply {
                text = "Seat technique score: --"
                textSize = 13f
            })
            if (!seat.isComplete()) {
                inner.addView(TextView(requireContext()).apply {
                    text = "Warning: incomplete seat (needs Port + Starboard)"
                    textSize = 12f
                    setPadding(0, 8, 0, 0)
                })
            }
            card.addView(inner)
            b.containerSensors.addView(card)
        }
    }

    private fun buildCard(): MaterialCardView {
        return MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            strokeWidth = 2
            strokeColor = ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
            setContentPadding(24, 16, 24, 16)
        }
    }

    private fun buildScullingSideBlock(label: String, sensor: Sensor?): View {
        if (sensor == null) {
            return TextView(requireContext()).apply {
                text = "$label: (empty)"
                textSize = 14f
                setPadding(24, 8, 0, 0)
            }
        }

        val status = TextView(requireContext()).apply {
            text = "$label status: DISCONNECTED"
            textSize = 14f
            setPadding(24, 8, 0, 0)
        }
        val spm = TextView(requireContext()).apply {
            text = "SPM: --"
            textSize = 15f
            setPadding(24, 2, 0, 0)
        }
        val strokes = TextView(requireContext()).apply {
            text = "Strokes: 0"
            textSize = 15f
            setPadding(24, 2, 0, 0)
        }
        val name = sensor.name?.trim()?.takeIf { it.isNotEmpty() } ?: sensor.mac
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = "$label: $name"
                textSize = 14f
                setPadding(24, 10, 0, 0)
            })
            addView(status)
            addView(spm)
            addView(strokes)
        }
        rowViews[sensor.id] = RowViews(status, spm, strokes)
        return wrapper
    }

    private fun displayStatus(running: Boolean, connected: Boolean, status: String): String {
        return when {
            running && !connected -> "DISCONNECTED (reconnecting…)"
            running -> status
            connected -> "CONNECTED (ready)"
            else -> "DISCONNECTED"
        }
    }

    private fun ensureInitialStatus() {
        val running = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)
        val serviceActive = prefs.getBoolean(KEY_SERVICE_ACTIVE, false)
        if (!running && !serviceActive) {
            // Reset per-sensor status entries
            sensorsViewModel.sensors.value.orEmpty().forEach { sensor ->
                prefs.edit()
                    .putString(statusKey(sensor.mac), "DISCONNECTED")
                    .putBoolean(connectedKey(sensor.mac), false)
                    .putInt(strokesKey(sensor.mac), 0)
                    .putInt(spmKey(sensor.mac), 0)
                    .apply()
            }
        }
    }

    private fun ensureConnectedIfNeeded(forceRefresh: Boolean = false) {
        val running = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)
        if (running) return

        // Never try to start a connectedDevice FGS without BLE permissions.
        if (!BlePermissions.hasConnectPermission(requireContext())) return

        val sensors = sensorsViewModel.sensors.value.orEmpty()
        if (sensors.isEmpty()) return
        val signature = sensors.map { it.mac }.sorted().joinToString("|")
        val serviceActive = prefs.getBoolean(KEY_SERVICE_ACTIVE, false)
        val mappingVersion = SeatMappingVersionStore.get(requireContext())
        val appliedMappingVersion = prefs.getInt(KEY_APPLIED_MAPPING_VERSION, -1)
        val mappingRefreshNeeded = mappingVersion != appliedMappingVersion
        val hasDisconnectedSensor = sensors.any { !prefs.getBoolean(connectedKey(it.mac), false) }
        if (!forceRefresh &&
            serviceActive &&
            signature == lastConnectOnlySignature &&
            !mappingRefreshNeeded &&
            !hasDisconnectedSensor
        ) return

        // Immediately update UI to a non-stale state while the service spins up.
        sensors.forEach { sensor ->
            prefs.edit()
                .putString(statusKey(sensor.mac), "CONNECTING")
                .putBoolean(connectedKey(sensor.mac), false)
                .apply()
        }
        IntervalRecordingService.connectOnly(requireContext(), sensors)
        lastConnectOnlySignature = signature
        prefs.edit().putInt(KEY_APPLIED_MAPPING_VERSION, mappingVersion).apply()
    }

    private fun scheduleStopWatchdog() {
        handler.postDelayed({
            val stillRunning = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)
            if (stillRunning) {
                binding.buttonInterval.text = "Stop Interval"
                Toast.makeText(requireContext(), "Stop still in progress...", Toast.LENGTH_SHORT).show()
            } else {
                binding.buttonInterval.text = "Start Interval"
            }
        }, 2500L)
    }

    companion object {
        private const val PREFS = "syncrow_prefs"
        private const val KEY_INTERVAL_RUNNING = "interval_running"
        private const val KEY_STATUS = "live_status"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_APPLIED_MAPPING_VERSION = "diag_last_applied_mapping_version"

        private fun statusKey(mac: String) = "live_status_$mac"
        private fun strokesKey(mac: String) = "live_strokes_$mac"
        private fun spmKey(mac: String) = "live_spm_$mac"
        private fun connectedKey(mac: String) = "live_connected_$mac"
        private fun latenessKey(mac: String) = "live_lateness_$mac"
        private fun syncStatusKey(mac: String) = "live_syncstatus_$mac"
    }
}
