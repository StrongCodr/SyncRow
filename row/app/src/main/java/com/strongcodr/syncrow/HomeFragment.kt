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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.strongcodr.syncrow.databinding.FragmentHomeBinding
import android.net.Uri

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val sensorsViewModel: SensorsViewModel by activityViewModels()
    private var lastRenderedMode: RowingMode? = null

    private data class SweepRowRef(
        val status: TextView
    )

    private data class ScullingSideRef(
        val label: String,
        val displayName: String,
        val status: TextView
    )

    private val sweepRows: MutableMap<Long, SweepRowRef> = mutableMapOf()
    private val scullingSides: MutableMap<Long, ScullingSideRef> = mutableMapOf()

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStartAfterNotifications = false
    private var lastConnectOnlySignature: String? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            val running = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)
            binding.buttonStartInterval.text = if (running) "Stop Interval" else "Start Interval"
            val sensors = sensorsViewModel.sensors.value.orEmpty()
            val mode = RowingModeStore.getUiMode(requireContext())
            if (mode != lastRenderedMode) {
                renderSensors(sensors)
            } else {
                updateDynamicUi(sensors, mode)
            }
            handler.postDelayed(this, 500L)
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Notifications.ensureChannels(requireContext())

        sensorsViewModel.sensors.observe(viewLifecycleOwner) { sensors ->
            renderSensors(sensors)
            if (sensors.isEmpty()) {
                lastConnectOnlySignature = null
                return@observe
            }
            ensureConnectedIfNeeded()
        }

        binding.buttonStartInterval.setOnClickListener {
            onStartStopPressed()
        }
    }

    override fun onStart() {
        super.onStart()
        ensureInitialStatus()
        ensureConnectedIfNeeded()
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
            // Stop without navigating
            prefs.edit().putBoolean(KEY_INTERVAL_RUNNING, false).apply()
            binding.buttonStartInterval.text = "Start Interval"
            IntervalRecordingService.stop(requireContext())
            scheduleStopWatchdog()
            return
        }

        // Starting: enforce notifications permission first
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
            .setPositiveButton("Open Settings") { _, _ -> openNotificationSettings() }
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

        // Starting: enforce BLE connect permission (required to start connectedDevice FGS on modern Android)
        if (!BlePermissions.hasConnectPermission(requireContext())) {
            requestBleNow()
            return
        }

        // Start service using all sensors, then go to Live Row
        val legacyIds = sensors.mapIndexed { idx, _ -> "Seat ${sensors.size - idx}" }
        Log.d(
            "SYNCROW",
            "Pre-start proof only (not upload proof): legacy sensor_id labels=$legacyIds uiMode=${RowingModeStore.getUiMode(requireContext())}"
        )
        IntervalRecordingService.start(
            context = requireContext(),
            sensors = sensors
        )
        Log.d("SYNCROW", "Recorder start requested with legacy sensor_id labels=$legacyIds")

        findNavController().navigate(R.id.liveRowFragment)
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

    private fun ensureInitialStatus() {
        val running = prefs.getBoolean(KEY_INTERVAL_RUNNING, false)
        val serviceActive = prefs.getBoolean(KEY_SERVICE_ACTIVE, false)
        if (!running && !serviceActive) {
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
                binding.buttonStartInterval.text = "Stop Interval"
                Toast.makeText(requireContext(), "Stop still in progress...", Toast.LENGTH_SHORT).show()
            } else {
                binding.buttonStartInterval.text = "Start Interval"
            }
        }, 2500L)
    }

    private fun renderSensors(sensors: List<Sensor>) {
        val emptyView = binding.textEmpty
        val container = binding.sensorsContainer
        val mode = RowingModeStore.getUiMode(requireContext())
        lastRenderedMode = mode

        container.removeAllViews()
        sweepRows.clear()
        scullingSides.clear()
        binding.textModeBadge.text = "Mode: ${if (mode == RowingMode.SWEEP) "Sweep" else "Sculling"}"

        if (sensors.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            container.visibility = View.GONE
            emptyView.text = "No sensors registered yet"
            binding.textCrewSummary.text = if (mode == RowingMode.SWEEP) "0/0 seats connected" else "0/0 oars connected"
            return
        }

        emptyView.visibility = View.GONE
        container.visibility = View.VISIBLE

        if (mode == RowingMode.SWEEP) {
            val total = sensors.size
            sensors.forEachIndexed { idx, sensor ->
                val seatNumber = total - idx
                val row = createSweepSensorBubble(sensor, seatNumber)
                container.addView(row.first)
                sweepRows[sensor.id] = row.second
            }
            updateDynamicUi(sensors, mode)
            return
        }

        val seats = CrewLayout.toScullingSeats(sensors)
        seats.forEach { seat ->
            container.addView(createScullingSensorBubble(seat))
        }
        updateDynamicUi(sensors, mode)
    }

    private fun updateDynamicUi(sensors: List<Sensor>, mode: RowingMode) {
        if (mode == RowingMode.SWEEP) {
            val total = sensors.size
            var connectedSeats = 0
            sensors.forEach { sensor ->
                val connected = prefs.getBoolean(connectedKey(sensor.mac), false)
                if (connected) connectedSeats += 1
                sweepRows[sensor.id]?.status?.text = "Status: ${if (connected) "Connected" else "Disconnected"}"
            }
            binding.textCrewSummary.text = "$connectedSeats/$total seats connected"
            return
        }

        val seats = CrewLayout.toScullingSeats(sensors)
        val totalSlots = seats.size * 2
        var connectedSlots = 0
        var hasIncompleteSeat = false
        seats.forEach { seat ->
            seat.port?.let { sensor ->
                val connected = prefs.getBoolean(connectedKey(sensor.mac), false)
                if (connected) connectedSlots += 1
                scullingSides[sensor.id]?.let { ref ->
                    ref.status.text = "${ref.label}:\n  ${ref.displayName} (${if (connected) "Connected" else "Disconnected"})"
                }
            }
            seat.starboard?.let { sensor ->
                val connected = prefs.getBoolean(connectedKey(sensor.mac), false)
                if (connected) connectedSlots += 1
                scullingSides[sensor.id]?.let { ref ->
                    ref.status.text = "${ref.label}:\n  ${ref.displayName} (${if (connected) "Connected" else "Disconnected"})"
                }
            }
            if (!seat.isComplete()) hasIncompleteSeat = true
        }
        val warning = if (hasIncompleteSeat) "  (incomplete seat detected)" else ""
        binding.textCrewSummary.text = "$connectedSlots/$totalSlots oars connected$warning"
    }

    private fun createSweepSensorBubble(sensor: Sensor, seatIndex: Int): Pair<View, SweepRowRef> {
        val context = requireContext()

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = ContextCompat.getDrawable(context, R.drawable.bg_sensor_bubble)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val customName = sensor.name?.trim()?.takeIf { it.isNotEmpty() }
        val seatView = TextView(context).apply {
            text = if (customName == null) "Seat $seatIndex" else "Seat $seatIndex - $customName"
            textSize = 16f
            setPadding(0, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val menuButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            background = null
            contentDescription = "Sensor options"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { view -> showSensorMenu(view, seatIndex) }
        }

        headerRow.addView(seatView)
        headerRow.addView(menuButton)

        bubble.addView(headerRow)
        val statusView = TextView(context).apply {
            text = "Status: Disconnected"
            textSize = 13f
            setPadding(0, 8, 0, 0)
        }
        bubble.addView(statusView)

        val latenessView = TextView(context).apply {
            text = "Lateness vs Stroke: -- ms"
            textSize = 13f
            setPadding(0, 10, 0, 0)
        }
        bubble.addView(latenessView)
        bubble.addView(TextView(context).apply {
            text = "Technique: --/100"
            textSize = 13f
            setPadding(0, 6, 0, 0)
        })

        return bubble to SweepRowRef(
            status = statusView
        )
    }

    private fun createScullingSensorBubble(seat: CrewLayout.ScullingSeat): View {
        val context = requireContext()
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = ContextCompat.getDrawable(context, R.drawable.bg_sensor_bubble)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }

        val headerRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val seatTitle = TextView(context).apply {
            text = "Seat ${seat.seatNumber}"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val menuButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            background = null
            contentDescription = "Seat options"
            setOnClickListener { view -> showSensorMenu(view, seat.seatNumber) }
        }
        headerRow.addView(seatTitle)
        headerRow.addView(menuButton)
        bubble.addView(headerRow)

        bubble.addView(scullingSideText("Starboard", seat.port))
        bubble.addView(scullingSideText("Port", seat.starboard))
        bubble.addView(TextView(context).apply {
            text = "Lateness vs Stroke: -- ms"
            textSize = 13f
            setPadding(0, 10, 0, 0)
        })
        bubble.addView(TextView(context).apply {
            text = "Technique: --/100"
            textSize = 13f
            setPadding(0, 6, 0, 0)
        })
        if (!seat.isComplete()) {
            bubble.addView(TextView(context).apply {
                text = "Warning: incomplete seat (needs Port + Starboard)"
                textSize = 12f
                setPadding(0, 8, 0, 0)
            })
        }
        return bubble
    }

    private fun scullingSideText(label: String, sensor: Sensor?): TextView {
        val name = sensor?.name?.trim()?.takeIf { it.isNotEmpty() } ?: sensor?.mac ?: "(empty)"
        val view = TextView(requireContext()).apply {
            text = if (sensor == null) "$label:\n  $name (Missing)" else "$label:\n  $name (Disconnected)"
            textSize = 14f
            setPadding(24, 10, 0, 0)
        }
        if (sensor != null) {
            scullingSides[sensor.id] = ScullingSideRef(
                label = label,
                displayName = name,
                status = view
            )
        }
        return view
    }

    private fun showSensorMenu(anchor: View, seatIndex: Int) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add("Technique info")
        popup.menu.add("Connection details")
        popup.menu.add("Reassign sensor")
        popup.menu.add("Remove/replace")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Technique info" -> {
                    showTechniqueInfoDialog(seatIndex)
                    true
                }
                "Connection details" -> {
                    showSimpleSeatDialog("Connection details", seatIndex, "Connection details placeholder.")
                    true
                }
                "Reassign sensor" -> {
                    showSimpleSeatDialog("Reassign sensor", seatIndex, "Reassign sensor option placeholder.")
                    true
                }
                "Remove/replace" -> {
                    showSimpleSeatDialog("Remove/replace", seatIndex, "Remove/replace option placeholder.")
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    private fun showTechniqueInfoDialog(seatIndex: Int) {
        val message = "Seat $seatIndex\n\nTechnique info placeholder."

        AlertDialog.Builder(requireContext())
            .setTitle("Technique info")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSimpleSeatDialog(title: String, seatIndex: Int, body: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage("Seat $seatIndex\n\n$body")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sweepRows.clear()
        scullingSides.clear()
        lastRenderedMode = null
        _binding = null
    }

    companion object {
        private const val PREFS = "syncrow_prefs"
        private const val KEY_INTERVAL_RUNNING = "interval_running"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_APPLIED_MAPPING_VERSION = "diag_last_applied_mapping_version"

        private fun statusKey(mac: String) = "live_status_$mac"
        private fun strokesKey(mac: String) = "live_strokes_$mac"
        private fun spmKey(mac: String) = "live_spm_$mac"
        private fun connectedKey(mac: String) = "live_connected_$mac"
    }
}
