package com.strongcodr.syncrow

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.strongcodr.syncrow.model.IntervalMeta
import com.strongcodr.syncrow.model.IntervalUpload
import com.strongcodr.syncrow.model.LocationSample
import com.strongcodr.syncrow.model.LocationUpload
import com.strongcodr.syncrow.model.SeatSummary
import com.strongcodr.syncrow.model.SensorDiagnostic
import com.strongcodr.syncrow.model.SessionSummary
import com.strongcodr.syncrow.model.SensorSample
import com.strongcodr.syncrow.model.SensorSyncStatus
import com.strongcodr.syncrow.model.SyncStatus
import com.strongcodr.syncrow.network.ApiClient
import com.strongcodr.syncrow.storage.DiagnosticsStore
import com.strongcodr.syncrow.storage.IntervalIndexStore
import com.strongcodr.syncrow.storage.IntervalNamesStore
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IntervalRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.strongcodr.syncrow.action.START_INTERVAL"
        const val ACTION_STOP = "com.strongcodr.syncrow.action.STOP_INTERVAL"
        const val ACTION_CONNECT_ONLY = "com.strongcodr.syncrow.action.CONNECT_ONLY"
        private const val ACTION_RECONNECT_ALL = "com.strongcodr.syncrow.action.RECONNECT_ALL"
        private const val ACTION_SYNC_PENDING = "com.strongcodr.syncrow.action.SYNC_PENDING"
        private const val ACTION_APP_FOREGROUND = "com.strongcodr.syncrow.action.APP_FOREGROUND"
        private const val ACTION_APP_BACKGROUND = "com.strongcodr.syncrow.action.APP_BACKGROUND"

        const val EXTRA_SENSOR_MAC = "extra_sensor_mac"
        const val EXTRA_SENSOR_ID = "extra_sensor_id"
        const val EXTRA_SEAT = "extra_seat"
        private const val EXTRA_SENSOR_MACS = "extra_sensor_macs"
        private const val EXTRA_SENSOR_IDS = "extra_sensor_ids"
        private const val EXTRA_SENSOR_SEATS = "extra_sensor_seats"
        private const val EXTRA_SENSOR_DISPLAY_NAMES = "extra_sensor_display_names"
        private const val EXTRA_SENSOR_ROLES = "extra_sensor_roles"
        private const val EXTRA_FORCE_RECONNECT = "extra_force_reconnect"

        private const val ONGOING_NOTIF_ID = 42

        private const val PREFS = "syncrow_prefs"
        private const val KEY_INTERVAL_RUNNING = "interval_running"
        private const val KEY_INTERVAL_ID = "interval_id"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_TIME_PACKET_ENABLED = "debug_time_packet_enabled"

        /** Persisted state for the debug TIME-packet toggle. Read at app start to seed
         *  [BleDeviceClient.enableTimePacket]; written by the diag screen on toggle. */
        fun isTimePacketEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TIME_PACKET_ENABLED, false)
        }

        fun setTimePacketEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TIME_PACKET_ENABLED, enabled)
                .apply()
            BleDeviceClient.enableTimePacket = enabled
        }
        private const val KEY_LAST_FOREGROUND_ELAPSED = "diag_last_foreground_elapsed"
        private const val KEY_LAST_BACKGROUND_ELAPSED = "diag_last_background_elapsed"
        private const val KEY_PENDING_SOFT_RESET = "diag_pending_soft_reset"
        private const val KEY_PENDING_SOFT_RESET_REASON = "diag_pending_soft_reset_reason"
        private const val RESUME_SELF_HEAL_THRESHOLD_MS = 10 * 60 * 1000L
        private const val SOFT_RESET_RECONNECT_DELAY_MS = 1500L
        private const val CONNECT_ATTEMPT_STALE_MS = 12_000L
        private const val STATUS_147_TIMEOUT = 147

        private fun statusKey(mac: String) = "live_status_$mac"
        private fun strokesKey(mac: String) = "live_strokes_$mac"
        private fun spmKey(mac: String) = "live_spm_$mac"
        private fun connectedKey(mac: String) = "live_connected_$mac"
        private fun latenessKey(mac: String) = "live_lateness_$mac"
        private fun syncStatusKey(mac: String) = "live_syncstatus_$mac"
        fun hzKey(mac: String) = "live_hz_$mac"

        private val nextIntervalId = AtomicLong(System.currentTimeMillis())

        private fun hasConnectedDevicePermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }

        private fun isServiceActive(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SERVICE_ACTIVE, false)
        }

        // SensorLabels and the pure labeling logic live in SensorLabelBuilder.kt so unit
        // tests can exercise them without instantiating the service. Keep this thin
        // wrapper because the recording-mode pref still needs to be read against a Context.
        const val COX_LABEL = COX_LABEL_VALUE

        private fun buildSensorLabels(context: Context, sensors: List<Sensor>): SensorLabels {
            return buildSensorLabels(sensors, RowingModeStore.getRecordingMode(context))
        }

        fun start(context: Context, sensors: List<Sensor>) {
            val labels = buildSensorLabels(context, sensors)
            val i = Intent(context, IntervalRecordingService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_SENSOR_MACS, ArrayList(sensors.map { it.mac }))
                putStringArrayListExtra(EXTRA_SENSOR_IDS, ArrayList(labels.ids))
                putStringArrayListExtra(EXTRA_SENSOR_SEATS, ArrayList(labels.seats))
                putStringArrayListExtra(EXTRA_SENSOR_DISPLAY_NAMES, ArrayList(labels.displayNames))
                putStringArrayListExtra(EXTRA_SENSOR_ROLES, ArrayList(labels.roles))
            }
            if (hasConnectedDevicePermission(context)) {
                context.startForegroundService(i)
            } else {
                // Avoid starting an FGS when Android will refuse it; the service will show a notification if needed.
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, IntervalRecordingService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }

        fun connectOnly(context: Context, sensors: List<Sensor>) {
            val labels = buildSensorLabels(context, sensors)
            val i = Intent(context, IntervalRecordingService::class.java).apply {
                action = ACTION_CONNECT_ONLY
                putStringArrayListExtra(EXTRA_SENSOR_MACS, ArrayList(sensors.map { it.mac }))
                putStringArrayListExtra(EXTRA_SENSOR_IDS, ArrayList(labels.ids))
                putStringArrayListExtra(EXTRA_SENSOR_SEATS, ArrayList(labels.seats))
                putStringArrayListExtra(EXTRA_SENSOR_DISPLAY_NAMES, ArrayList(labels.displayNames))
                putStringArrayListExtra(EXTRA_SENSOR_ROLES, ArrayList(labels.roles))
            }
            // CONNECT_ONLY is used for live UI connection status while the app is in the foreground.
            // Always start as a normal service to avoid ForegroundServiceDidNotStartInTime crashes.
            context.startService(i)
        }

        fun reconnectAll(context: Context, sensors: List<Sensor>) {
            val labels = buildSensorLabels(context, sensors)
            val i = Intent(context, IntervalRecordingService::class.java).apply {
                action = ACTION_RECONNECT_ALL
                putStringArrayListExtra(EXTRA_SENSOR_MACS, ArrayList(sensors.map { it.mac }))
                putStringArrayListExtra(EXTRA_SENSOR_IDS, ArrayList(labels.ids))
                putStringArrayListExtra(EXTRA_SENSOR_SEATS, ArrayList(labels.seats))
                putStringArrayListExtra(EXTRA_SENSOR_DISPLAY_NAMES, ArrayList(labels.displayNames))
                putStringArrayListExtra(EXTRA_SENSOR_ROLES, ArrayList(labels.roles))
                putExtra(EXTRA_FORCE_RECONNECT, true)
            }
            context.startService(i)
        }

        fun notifyAppForeground(context: Context) {
            if (!isServiceActive(context)) return
            val i = Intent(context, IntervalRecordingService::class.java).apply { action = ACTION_APP_FOREGROUND }
            try {
                context.startService(i)
            } catch (e: Exception) {
                Log.e("SYNCROW", "notifyAppForeground failed: ${e.message}", e)
            }
        }

        fun notifyAppBackground(context: Context) {
            if (!isServiceActive(context)) return
            val i = Intent(context, IntervalRecordingService::class.java).apply { action = ACTION_APP_BACKGROUND }
            try {
                context.startService(i)
            } catch (e: Exception) {
                Log.e("SYNCROW", "notifyAppBackground failed: ${e.message}", e)
            }
        }

        fun syncPending(context: Context) {
            val i = Intent(context, IntervalRecordingService::class.java).apply { action = ACTION_SYNC_PENDING }
            try {
                context.startService(i)
            } catch (e: Exception) {
                Log.e("SYNCROW", "syncPending failed: ${e.message}", e)
            }
        }
    }

    private enum class StopReason { USER_STOP, RECENTS_REMOVED }
    private enum class StrokeState { FORWARD, BACKWARD, UNKNOWN }

    // Mix of threads touches this: BLE binder callbacks (connected/lastSeen/*), coroutine
    // scopes (everything else). @field:Volatile on the fields read cross-thread without
    // snapshotting; everything else is either snapshot-read via .toList() or touched from
    // a single thread in practice.
    private data class ActiveSensor(
        val mac: String,
        var id: String,
        var seat: String?,
        var seatIndex: Int,
        var displayName: String,
        var role: SensorRole = SensorRole.SEAT,
        val client: BleDeviceClient,
        val samples: MutableList<SensorSample> = mutableListOf(),
        val strokeTimesMs: ArrayDeque<Long> = ArrayDeque(64),
        var intervalId: Long = -1L,
        var lastStoredSampleMs: Long = 0L,
        @field:Volatile var lastSeenSampleMs: Long = 0L,
        // latestSampleMs/latestSample are written on the BLE callback thread and read
        // on the sampling loop (the `fresh` check + sample snapshot). Volatile so the
        // loop sees a coherent, current value — a torn/stale read would misclassify a
        // held tick.
        @field:Volatile var latestSampleMs: Long = 0L,
        @field:Volatile var latestSample: Normalized? = null,
        var lastFedBleMs: Long = 0L,   // only touched by the sampling loop; no barrier needed
        var strokeCount: Int = 0,
        var currentSpm: Int = 0,
        var maxSpm: Int = 0,
        var detectorState: StrokeState = StrokeState.UNKNOWN,
        var stateStartMs: Long = 0L,
        @field:Volatile var connected: Boolean = false,
        var reconnectJobActive: Boolean = false,
        var reconnectJob: Job? = null,
        var lastDisconnectNotifMs: Long = 0L,
        var connectInFlight: Boolean = false,
        var lastConnectAttemptElapsedMs: Long = 0L,
        var timeout147Count: Int = 0,
        val reconnectsThisWindow: AtomicInteger = AtomicInteger(0)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private val processStartElapsedMs: Long = SystemClock.elapsedRealtime()

    @Volatile private var intervalRunning = false
    @Volatile private var serviceActive = false
    private var serviceInForeground = false

    // ConcurrentHashMap — mutated from multiple coroutines (interval start/stop, idle
    // connect, connect-retry loops) and read from the diagnostics tick + health monitor.
    // Snapshot readers still use .toList() for point-in-time consistency over compound reads.
    private val activeSensors = ConcurrentHashMap<String, ActiveSensor>() // key: mac
    private val strokeAnalyzer = StrokeAnalyzer()
    @Volatile private var sessionIntervalId: Long = -1L
    private var healthMonitorActive = false
    private val pendingUploadActive = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var intervalStartWallMs: Long = 0L
    private var intervalStartElapsedMs: Long = 0L

    private val targetSamplePeriodMs = 10L // ~100 Hz
    private val minPhaseMs = 140L
    private val accelDeadband = 0.10f

    private var appIsForeground = true
    private var backgroundSinceMs: Long = 0L
    private var backgroundShutdownArmed = false
    private var softResetInProgress = false
    private var pendingResumeSoftReset = false
    private var pendingResumeSoftResetReason: String? = null

    private var lastNotifStatus = "IDLE"
    private val locationSamples: MutableList<LocationSample> = mutableListOf()
    private var latestLocation: LocationSample? = null
    private val locationDedupeWindowMs = 1000L

    private fun addLocationSampleDeduped(sample: LocationSample) {
        val last = locationSamples.lastOrNull()
        if (last != null &&
            last.latitude == sample.latitude &&
            last.longitude == sample.longitude &&
            sample.timestampMs - last.timestampMs < locationDedupeWindowMs
        ) {
            return
        }
        locationSamples.add(sample)
    }
    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null
    private val connectAttemptMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Seed the process-wide debug flag from persisted prefs so toggling the TIME packet
        // setting survives app/process restart. Same SharedPreferences key the diag screen
        // writes when the user taps the toggle.
        BleDeviceClient.enableTimePacket = prefs.getBoolean(KEY_TIME_PACKET_ENABLED, false)
        Notifications.ensureChannels(this)
        if (DEBUG_BLE) {
            Log.d("SYNCROW", "Service onCreate: processStartElapsedMs=$processStartElapsedMs")
        }
        registerNetworkCallback()
        uploadPendingIntervalsIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (prefs.getBoolean(KEY_INTERVAL_RUNNING, false)) {
                handleStaleIntervalStop("service restarted with null intent")
            }
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                if (!canStartConnectedDeviceFgs()) {
                    handleMissingBlePermission()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sensors = readSensorsFromIntent(intent)
                if (sensors.isEmpty()) return START_NOT_STICKY
                // Reject cox-only recordings before we commit to foreground promotion.
                // On Android 8+, startForegroundService must be paired with startForeground
                // within ~5s or the app crashes with ForegroundServiceDidNotStartInTime.
                // startInterval promotes via ensureForeground; bailing out there would miss
                // the deadline. Stop the service cleanly here instead.
                if (sensors.none { it.role == SensorRole.SEAT }) {
                    postEventNotification(
                        "Cannot start interval",
                        "Add at least one rower sensor to start recording."
                    )
                    Log.e("SYNCROW", "startInterval rejected: no rower sensors (cox-only or empty)")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInterval(sensors)
            }
            ACTION_STOP -> {
                if (!intervalRunning && prefs.getBoolean(KEY_INTERVAL_RUNNING, false)) {
                    handleStaleIntervalStop("stop requested but intervalRunning=false")
                } else {
                    stopInterval(StopReason.USER_STOP)
                }
            }
            ACTION_CONNECT_ONLY -> {
                val sensors = readSensorsFromIntent(intent)
                if (sensors.isEmpty()) return START_NOT_STICKY
                startIdleConnection(sensors)
            }
            ACTION_RECONNECT_ALL -> {
                if (intervalRunning) {
                    postEventNotification(
                        "Reconnect blocked",
                        "Stop interval before reconnecting sensors."
                    )
                    return START_STICKY
                }
                val sensors = readSensorsFromIntent(intent)
                if (sensors.isEmpty()) return START_NOT_STICKY
                forceReconnectIdleSensors(sensors)
            }
            ACTION_APP_FOREGROUND -> {
                val nowElapsed = SystemClock.elapsedRealtime()
                val lastForegroundElapsedMs = prefs.getLong(KEY_LAST_FOREGROUND_ELAPSED, -1L)
                val elapsedSinceForegroundMs = if (lastForegroundElapsedMs > 0L) {
                    nowElapsed - lastForegroundElapsedMs
                } else {
                    -1L
                }
                val lastBackgroundElapsedMs = prefs.getLong(KEY_LAST_BACKGROUND_ELAPSED, -1L)
                val elapsedSinceBackgroundMs = if (lastBackgroundElapsedMs > 0L) {
                    nowElapsed - lastBackgroundElapsedMs
                } else {
                    -1L
                }
                appIsForeground = true
                backgroundShutdownArmed = false
                prefs.edit().putLong(KEY_LAST_FOREGROUND_ELAPSED, nowElapsed).apply()
                logResumeDiagnostics(
                    lastForegroundElapsedMs,
                    elapsedSinceForegroundMs,
                    lastBackgroundElapsedMs,
                    elapsedSinceBackgroundMs
                )
                if (intervalRunning) {
                    ensureForeground()
                    setOngoingNotification(lastNotifStatus)
                }
                setUiStatusIfIdle()
                if (maybeRunPersistedSoftResetIfNeeded()) {
                    return START_STICKY
                }
                maybeRunResumeSoftReset(elapsedSinceBackgroundMs)
            }
            ACTION_APP_BACKGROUND -> {
                appIsForeground = false
                backgroundSinceMs = SystemClock.elapsedRealtime()
                prefs.edit().putLong(KEY_LAST_BACKGROUND_ELAPSED, backgroundSinceMs).apply()
                armBackgroundShutdownIfNeeded()
            }
            ACTION_SYNC_PENDING -> uploadPendingIntervalsIfNeeded()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopInterval(StopReason.RECENTS_REMOVED)
        disconnectAndStopService()
        super.onTaskRemoved(rootIntent)
    }

    private data class IntentSensor(
        val mac: String,
        val id: String,
        val seat: String?,
        val seatIndex: Int,
        val displayName: String,
        val role: SensorRole
    )

    private fun readSensorsFromIntent(intent: Intent): List<IntentSensor> {
        val macs = intent.getStringArrayListExtra(EXTRA_SENSOR_MACS)
        val ids = intent.getStringArrayListExtra(EXTRA_SENSOR_IDS)
        val seats = intent.getStringArrayListExtra(EXTRA_SENSOR_SEATS)
        val displayNames = intent.getStringArrayListExtra(EXTRA_SENSOR_DISPLAY_NAMES)
        val roles = intent.getStringArrayListExtra(EXTRA_SENSOR_ROLES)
        if (macs != null && ids != null) {
            // Prefer the explicit role extra (decouples cox detection from label equality).
            // Fall back to seat=="Cox" only for legacy intents from older callers — every
            // in-tree caller now sets the roles extra.
            val rolesResolved: List<SensorRole> = macs.indices.map { idx ->
                val raw = roles?.getOrNull(idx)
                val byRole = raw?.let { name -> runCatching { SensorRole.valueOf(name) }.getOrNull() }
                if (byRole != null) return@map byRole
                val fallbackSeat = seats?.getOrNull(idx) ?: ids.getOrNull(idx) ?: macs[idx]
                if (fallbackSeat == COX_LABEL) SensorRole.COX else SensorRole.SEAT
            }
            // Cox = seatIndex 0. Rowers get seatIndex = user-facing seat number (same
            // formula as SensorLabelBuilder's "Seat N"): first in the list is the
            // highest number = STROKE, last is seat 1 = bow. StrokeAnalyzer references
            // the highest seat number (stroke), so seatIndex carries the seat number.
            val rowerCount = rolesResolved.count { it == SensorRole.SEAT }
            var rowerIdx = 0
            return macs.mapIndexedNotNull { idx, mac ->
                val sid = ids.getOrNull(idx) ?: mac
                val seat = seats?.getOrNull(idx) ?: sid
                val displayName = displayNames?.getOrNull(idx) ?: seat
                val role = rolesResolved[idx]
                val seatIndex = if (role == SensorRole.COX) {
                    0
                } else {
                    val n = rowerCount - rowerIdx
                    rowerIdx++
                    n
                }
                IntentSensor(mac, sid, seat, seatIndex, displayName, role)
            }
        }

        val mac = intent.getStringExtra(EXTRA_SENSOR_MAC) ?: return emptyList()
        val sid = intent.getStringExtra(EXTRA_SENSOR_ID) ?: mac
        val st = intent.getStringExtra(EXTRA_SEAT) ?: sid
        val role = if (st == COX_LABEL) SensorRole.COX else SensorRole.SEAT
        val seatIndex = if (role == SensorRole.COX) 0 else 1
        return listOf(IntentSensor(mac, sid, st, seatIndex, st, role))
    }

    private fun startInterval(sensors: List<IntentSensor>) {
        // Caller (onStartCommand) guarantees at least one rower sensor — cox-only is
        // rejected upstream so we never hit the foreground-service deadline on reject.

        if (!serviceActive) {
            serviceActive = true
        }
        if (!ensureForeground()) {
            stopSelf()
            return
        }

        intervalRunning = true
        sessionIntervalId = nextIntervalId.incrementAndGet()
        intervalStartWallMs = System.currentTimeMillis()
        intervalStartElapsedMs = SystemClock.elapsedRealtime()
        locationSamples.clear()
        latestLocation = null
        strokeAnalyzer.reset()

        prefs.edit()
            .putBoolean(KEY_INTERVAL_RUNNING, true)
            .putBoolean(KEY_SERVICE_ACTIVE, true)
            .apply()

        startHealthMonitorIfNeeded()
        startSamplingLoopIfNeeded()
        startDiagnosticsLoopIfNeeded()
        startLocationUpdatesIfNeeded()

        val incomingMacs = sensors.map { it.mac }.toSet()
        activeSensors.values.filter { it.mac !in incomingMacs }.forEach { it.client.disconnect() }
        activeSensors.keys.retainAll(incomingMacs)

        sensors.forEach { s ->
            val existing = activeSensors[s.mac]
            if (existing == null) {
                val client = BleDeviceClient(this)
                val active = ActiveSensor(
                    mac = s.mac,
                    id = s.id,
                    seat = s.seat,
                    seatIndex = s.seatIndex,
                    displayName = s.displayName,
                    role = s.role,
                    client = client,
                    intervalId = sessionIntervalId,
                    stateStartMs = System.currentTimeMillis()
                )
                activeSensors[s.mac] = active
                connectSensor(active)
            } else {
                existing.id = s.id
                existing.seat = s.seat
                existing.seatIndex = s.seatIndex
                existing.displayName = s.displayName
                existing.role = s.role
                existing.intervalId = sessionIntervalId
                existing.samples.clear()
                existing.strokeTimesMs.clear()
                existing.strokeCount = 0
                existing.currentSpm = 0
                existing.maxSpm = 0
                existing.lastStoredSampleMs = 0L
                existing.lastSeenSampleMs = 0L
                existing.latestSampleMs = 0L
                existing.lastFedBleMs = 0L
                existing.latestSample = null
                existing.detectorState = StrokeState.UNKNOWN
                existing.stateStartMs = System.currentTimeMillis()
                connectSensor(existing)
            }

            // Cox doesn't row — exclude from stroke detection. Its samples still flow to
            // disk and InfluxDB as regular IMU data, just not through StrokeAnalyzer.
            if (s.role == SensorRole.SEAT) {
                strokeAnalyzer.addSensor(s.mac, s.seatIndex)
            }

            prefs.edit()
                .putString(statusKey(s.mac), "RECORDING")
                .putBoolean(connectedKey(s.mac), false)
                .putInt(strokesKey(s.mac), 0)
                .putInt(spmKey(s.mac), 0)
                .putLong(latenessKey(s.mac), Long.MIN_VALUE)
                .putString(syncStatusKey(s.mac), SensorSyncStatus.CALIBRATING.name)
                .apply()
        }

        setOngoingNotification("RECORDING")
        postEventNotification("Interval started", "SyncRow is recording.")
    }

    private fun startIdleConnection(sensors: List<IntentSensor>) {
        // Best-effort connections while the app is in the foreground (Live Row screen).
        // Keep this as a normal service (no startForeground) so permission state can't crash the app.
        if (!serviceActive) {
            serviceActive = true
        }

        intervalRunning = false
        stopSamplingLoop()
        stopLocationUpdates()

        prefs.edit()
            .putBoolean(KEY_SERVICE_ACTIVE, true)
            .putBoolean(KEY_INTERVAL_RUNNING, false)
            .apply()

        startHealthMonitorIfNeeded()
        startDiagnosticsLoopIfNeeded()

        val incomingMacs = sensors.map { it.mac }.toSet()
        activeSensors.values.filter { it.mac !in incomingMacs }.forEach { it.client.disconnect() }
        activeSensors.keys.retainAll(incomingMacs)

        sensors.forEach { s ->
            val existing = activeSensors[s.mac]
            if (existing == null) {
                val client = BleDeviceClient(this)
                val active = ActiveSensor(
                    mac = s.mac,
                    id = s.id,
                    seat = s.seat,
                    seatIndex = s.seatIndex,
                    displayName = s.displayName,
                    role = s.role,
                    client = client
                )
                activeSensors[s.mac] = active
                connectSensor(active)
            } else {
                existing.id = s.id
                existing.seat = s.seat
                existing.seatIndex = s.seatIndex
                existing.displayName = s.displayName
                existing.role = s.role
                connectSensor(existing)
            }
            prefs.edit()
                .putString(statusKey(s.mac), "CONNECTING")
                .putBoolean(connectedKey(s.mac), existing?.connected == true)
                .apply()
        }
    }

    private fun forceReconnectIdleSensors(sensors: List<IntentSensor>) {
        activeSensors.values.forEach { sensor ->
            sensor.reconnectJob?.cancel()
            sensor.reconnectJob = null
            sensor.reconnectJobActive = false
            sensor.connectInFlight = false
            sensor.connected = false
            try { sensor.client.disconnect() } catch (_: Exception) {}
        }
        activeSensors.clear()
        startIdleConnection(sensors)
    }

    private fun ensureForeground(): Boolean {
        if (serviceInForeground) return true
        setOngoingNotification("LIVE (idle)")
        return try {
            startForeground(ONGOING_NOTIF_ID, buildOngoingNotification("LIVE (idle)"))
            serviceInForeground = true
            true
        } catch (e: SecurityException) {
            Log.e("SYNCROW", "startForeground blocked (missing permission): ${e.message}", e)
            handleMissingBlePermission()
            false
        }
    }

    private fun stopInterval(reason: StopReason) {
        if (!intervalRunning) return

        intervalRunning = false

        val endElapsedMs = SystemClock.elapsedRealtime()
        val durationMs = (endElapsedMs - intervalStartElapsedMs).coerceAtLeast(0L)
        val sessionId = sessionIntervalId

        prefs.edit()
            .putBoolean(KEY_INTERVAL_RUNNING, false)
            .apply()

        setOngoingNotification("SAVING")

        val locationPayload = if (locationSamples.isNotEmpty()) {
            LocationUpload(
                interval_id = sessionId,
                samples = locationSamples.toList()
            )
        } else {
            Log.e("SYNCROW", "No location samples captured for intervalId=$sessionId")
            null
        }

        locationPayload?.let { saveLocationLocally(it) }

        // Write a single interval meta entry for the whole session (even with multiple sensors)
        val diagFileExists = DiagnosticsStore.readInterval(this, sessionId).isNotEmpty()
        IntervalIndexStore.upsert(
            this,
            IntervalMeta(
                id = sessionId,
                startTimeMillis = intervalStartWallMs,
                endTimeMillis = intervalStartWallMs + durationMs,
                syncStatus = SyncStatus.SYNCING,
                locationSyncStatus = if (locationPayload != null) SyncStatus.SYNCING else null,
                diagSyncStatus = if (diagFileExists) SyncStatus.SYNCING else null
            )
        )

        // Single “session saved” notification (not per sensor)
        postEventNotification("Interval ended", "SyncRow saved your session.")

        saveSessionSummary(sessionId = sessionId, durationMs = durationMs)

        val totalSensors = activeSensors.size.coerceAtLeast(1)
        val completed = AtomicInteger(0)
        val anyFailed = AtomicBoolean(false)
        val anyRetryable = AtomicBoolean(false)

        fun onUploadDone(success: Boolean, retryable: Boolean) {
            if (!success && !retryable) anyFailed.set(true)
            if (retryable) anyRetryable.set(true)
            val done = completed.incrementAndGet()
            if (done >= totalSensors) {
                val failed = anyFailed.get()
                val retryableAny = anyRetryable.get()
                IntervalIndexStore.updateStatus(
                    this@IntervalRecordingService,
                    sessionId,
                    when {
                        failed -> SyncStatus.FAILED
                        retryableAny -> SyncStatus.SYNCING
                        else -> SyncStatus.SYNCED
                    }
                )
                if (!failed && !retryableAny) {
                    postEventNotification(
                        "Data synced",
                        "Session synced. Open Previous Intervals for the recap."
                    )
                } else if (retryableAny && !failed) {
                    postEventNotification(
                        "Syncing",
                        "InfluxDB rate limit hit. Data saved on phone and will retry later."
                    )
                }
                setOngoingNotification("LIVE (idle)")
                setUiStatusIfIdle()
            }
        }

        activeSensors.values.forEach { sensor ->
            try {
                val avgSpm = if (durationMs > 0) {
                    ((sensor.strokeCount * 60_000.0) / durationMs).toInt().coerceIn(0, 120)
                } else {
                    0
                }
                val payload = IntervalUpload(
                    interval_id = sessionId,
                    sensor_id = sensor.id, // "Seat N"
                    seat = sensor.seat,
                    samples = sensor.samples.toList()
                )

                saveIntervalLocally(payload, sensor.id)

                scope.launch {
                    val canUpload = hasInternetNow()
                    if (!canUpload) {
                        IntervalIndexStore.updateStatus(this@IntervalRecordingService, sessionId, SyncStatus.SYNCING)
                        postEventNotification("Syncing", "Interval saved on phone. Will sync when internet returns.")
                        prefs.edit().putString(statusKey(sensor.mac), "SYNC PAUSED — OFFLINE").apply()
                        setOngoingNotification("LIVE (idle)")
                        setUiStatusIfIdle()
                        return@launch
                    }

                    Log.e(
                        "SYNCROW",
                        "Uploading intervalId=$sessionId samples=${payload.samples.size} mac=${bleMacForLog(sensor.mac)}"
                    )
                    prefs.edit().putString(statusKey(sensor.mac), "SYNCING").apply()
                    setOngoingNotification("SYNCING")

                    val (success, code) = try {
                        val intervalLabel = IntervalNamesStore.get(this@IntervalRecordingService, sessionId)
                            ?: "Interval_${sessionId}"
                        ApiClient.uploadInterval(payload, intervalLabel)
                    } catch (e: Exception) {
                        Log.e("SYNCROW", "Upload threw exception: ${e.javaClass.simpleName}: ${e.message}", e)
                        Pair(false, -1)
                    }
                    val retryable = code == 429

                    Log.e(
                        "SYNCROW",
                        "Upload finished: http=$code success=$success mac=${bleMacForLog(sensor.mac)}"
                    )

                    if (success) {
                        prefs.edit().putString(statusKey(sensor.mac), "SYNCED").apply()
                    } else if (retryable) {
                        prefs.edit().putString(statusKey(sensor.mac), "RATE LIMITED").apply()
                    } else {
                        anyFailed.set(true)
                        postEventNotification("Syncing", "Interval ended normally. Data was saved on phone and will sync when possible.")
                        prefs.edit().putString(statusKey(sensor.mac), "FAILED").apply()
                    }

                    onUploadDone(success, retryable)
                }
            } catch (e: Exception) {
                Log.e("SYNCROW", "stopInterval failed for mac=${sensor.mac}: ${e.message}", e)
                prefs.edit().putString(statusKey(sensor.mac), "FAILED").apply()
                setOngoingNotification("FAILED")
                IntervalIndexStore.updateStatus(this, sessionId, SyncStatus.FAILED)
                onUploadDone(false, false)
                setUiStatusIfIdle()
            }
        }

        if (locationPayload != null) {
            scope.launch {
                val canUpload = hasInternetNow()
                if (!canUpload) {
                    IntervalIndexStore.updateStatus(this@IntervalRecordingService, sessionId, SyncStatus.SYNCING)
                    IntervalIndexStore.updateLocationStatus(
                        this@IntervalRecordingService,
                        sessionId,
                        SyncStatus.SYNCING
                    )
                    postEventNotification("Syncing", "Interval saved on phone. Will sync when internet returns.")
                    setOngoingNotification("LIVE (idle)")
                    setUiStatusIfIdle()
                    return@launch
                }

                val (success, code) = try {
                    val intervalLabel = IntervalNamesStore.get(this@IntervalRecordingService, sessionId)
                        ?: "Interval_${sessionId}"
                    ApiClient.uploadLocation(locationPayload, intervalLabel)
                } catch (e: Exception) {
                    Log.e("SYNCROW", "Location upload threw exception: ${e.javaClass.simpleName}: ${e.message}", e)
                    Pair(false, -1)
                }
                val retryable = code == 429

                Log.e(
                    "SYNCROW",
                    "Location upload finished: success=$success samples=${locationPayload.samples.size} intervalId=$sessionId"
                )

                if (success) {
                    IntervalIndexStore.updateLocationStatus(
                        this@IntervalRecordingService,
                        sessionId,
                        SyncStatus.SYNCED
                    )
                } else if (retryable) {
                    IntervalIndexStore.updateLocationStatus(
                        this@IntervalRecordingService,
                        sessionId,
                        SyncStatus.SYNCING
                    )
                } else {
                    IntervalIndexStore.updateLocationStatus(
                        this@IntervalRecordingService,
                        sessionId,
                        SyncStatus.FAILED
                    )
                    postEventNotification(
                        "Syncing",
                        "Interval ended normally. Location was saved on phone and will sync when possible."
                    )
                }
            }
        }

        if (diagFileExists) {
            scope.launch {
                val canUpload = hasInternetNow()
                if (!canUpload) {
                    IntervalIndexStore.updateDiagStatus(
                        this@IntervalRecordingService,
                        sessionId,
                        SyncStatus.SYNCING
                    )
                    return@launch
                }

                val rows = try {
                    DiagnosticsStore.readInterval(this@IntervalRecordingService, sessionId)
                } catch (e: Exception) {
                    Log.e("SYNCROW", "Diag read failed: ${e.message}", e)
                    emptyList()
                }
                if (rows.isEmpty()) {
                    IntervalIndexStore.updateDiagStatus(
                        this@IntervalRecordingService,
                        sessionId,
                        SyncStatus.SYNCED
                    )
                    return@launch
                }

                val intervalLabel = IntervalNamesStore.get(this@IntervalRecordingService, sessionId)
                    ?: "Interval_${sessionId}"

                val (success, code) = try {
                    ApiClient.uploadDiagnostics(rows, intervalLabel)
                } catch (e: Exception) {
                    Log.e("SYNCROW", "Diag upload threw: ${e.javaClass.simpleName}: ${e.message}", e)
                    Pair(false, -1)
                }

                Log.e(
                    "SYNCROW",
                    "Diag upload finished: success=$success rows=${rows.size} intervalId=$sessionId"
                )

                val retryable = code == 429
                val newStatus = when {
                    success -> SyncStatus.SYNCED
                    retryable -> SyncStatus.SYNCING
                    else -> SyncStatus.FAILED
                }
                IntervalIndexStore.updateDiagStatus(
                    this@IntervalRecordingService,
                    sessionId,
                    newStatus
                )
            }
        }

        maybeRunDeferredSoftResetAfterStop()
    }

    private fun setUiStatusIfIdle() {
        if (intervalRunning) return
        if (appIsForeground) {
            activeSensors.values.forEach { sensor ->
                val status = when {
                    sensor.connected -> "CONNECTED (ready)"
                    sensor.connectInFlight || sensor.reconnectJobActive -> "DISCONNECTED (retrying…)"
                    else -> "DISCONNECTED"
                }
                prefs.edit()
                    .putString(statusKey(sensor.mac), status)
                    .putBoolean(connectedKey(sensor.mac), sensor.connected)
                    .apply()
            }
        }
        armBackgroundShutdownIfNeeded()
    }

    private fun armBackgroundShutdownIfNeeded() {
        if (intervalRunning) return
        if (appIsForeground) return
        if (!serviceActive) return
        if (backgroundShutdownArmed) return

        backgroundShutdownArmed = true
        scope.launch {
            val start = backgroundSinceMs
            delay(2 * 60 * 60 * 1000L) // 2 hours
            if (!intervalRunning && !appIsForeground && serviceActive && backgroundSinceMs == start) {
                disconnectAndStopService()
            }
        }
    }

    private fun disconnectAndStopService() {
        activeSensors.values.forEach { sensor ->
            sensor.reconnectJob?.cancel()
            sensor.reconnectJob = null
            sensor.reconnectJobActive = false
            sensor.connectInFlight = false
            prefs.edit()
                .putString(statusKey(sensor.mac), "IDLE")
                .putBoolean(connectedKey(sensor.mac), false)
                .apply()
            try { sensor.client.disconnect() } catch (_: Exception) {}
        }
        activeSensors.clear()
        serviceActive = false
        intervalRunning = false
        stopSamplingLoop()
        stopLocationUpdates()

        prefs.edit()
            .putBoolean(KEY_INTERVAL_RUNNING, false)
            .putBoolean(KEY_SERVICE_ACTIVE, false)
            .apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceInForeground = false
        stopSelf()
    }

    private fun handleStaleIntervalStop(reason: String) {
        Log.e("SYNCROW", "Clearing stale interval state: $reason")
        postEventNotification(
            "Interval stopped",
            "SyncRow reset a stale recording state after the service restarted."
        )
        disconnectAndStopService()
    }

    private fun connectSensor(sensor: ActiveSensor, reason: String = "unspecified") {
        if (!serviceActive) return
        if (sensor.connected || sensor.connectInFlight) return

        scope.launch {
            connectAttemptMutex.withLock {
                if (!serviceActive || sensor.connected || sensor.connectInFlight) return@withLock
                sensor.connectInFlight = true
                sensor.lastConnectAttemptElapsedMs = SystemClock.elapsedRealtime()
                if (!intervalRunning && appIsForeground) {
                    prefs.edit()
                        .putString(statusKey(sensor.mac), "CONNECTING")
                        .putBoolean(connectedKey(sensor.mac), false)
                        .apply()
                }
                if (DEBUG_BLE) {
                    Log.d(
                        "SYNCROW",
                        "Connect dispatch mac=${bleMacForLog(sensor.mac)} reason=$reason " +
                            "timeout147Count=${sensor.timeout147Count}"
                    )
                }
                sensor.client.connect(
                    deviceAddress = sensor.mac,
                    onSample = { ax, ay, az, wx, wy, wz, roll, pitch, yaw ->
                        // Seat-specific orientation normalization
                        val (nAx, nAy, nAz, nWx, nWy, nWz, nRoll, nPitch, nYaw) =
                            normalizeBySeat(sensor.seatIndex, ax, ay, az, wx, wy, wz, roll, pitch, yaw)

                        val now = System.currentTimeMillis()
                        sensor.lastSeenSampleMs = now
                        sensor.latestSampleMs = now
                        sensor.latestSample = Normalized(nAx, nAy, nAz, nWx, nWy, nWz, nRoll, nPitch, nYaw)
                        sensor.connected = true
                        sensor.connectInFlight = false
                        sensor.timeout147Count = 0
                        sensor.lastDisconnectNotifMs = 0L
                        if (intervalRunning) {
                            prefs.edit()
                                .putString(statusKey(sensor.mac), "RECORDING")
                                .putBoolean(connectedKey(sensor.mac), true)
                                .apply()
                        } else if (appIsForeground) {
                            prefs.edit()
                                .putString(statusKey(sensor.mac), "CONNECTED (ready)")
                                .putBoolean(connectedKey(sensor.mac), true)
                                .apply()
                        }
                    },
                    onStatus = { _ ->
                        if (!intervalRunning && appIsForeground) {
                            val status = when {
                                sensor.connected -> "CONNECTED (ready)"
                                sensor.connectInFlight -> "CONNECTING"
                                sensor.reconnectJobActive -> "DISCONNECTED (retrying…)"
                                else -> "DISCONNECTED"
                            }
                            prefs.edit()
                                .putString(statusKey(sensor.mac), status)
                                .putBoolean(connectedKey(sensor.mac), sensor.connected)
                                .apply()
                        }
                    },
                    onDisconnected = {
                        sensor.connectInFlight = false
                        sensor.connected = false
                        sensor.reconnectsThisWindow.incrementAndGet()
                        if (appIsForeground) prefs.edit()
                            .putString(statusKey(sensor.mac), "DISCONNECTED (reconnecting…)").apply()
                        prefs.edit().putBoolean(connectedKey(sensor.mac), false).apply()
                        setOngoingNotification("DISCONNECTED • reconnecting…")
                        maybeNotifySensorDisconnected(sensor)
                        startReconnectLoop(sensor)
                    },
                    onConnectionStateChange = { status, newState ->
                        if (status == STATUS_147_TIMEOUT) {
                            sensor.timeout147Count = (sensor.timeout147Count + 1).coerceAtMost(12)
                            if (DEBUG_BLE) {
                                Log.d(
                                    "SYNCROW",
                                    "Connect timeout(147) mac=${bleMacForLog(sensor.mac)} count=${sensor.timeout147Count}"
                                )
                            }
                        } else if (status == 0 && newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            sensor.timeout147Count = 0
                        }
                        if (status != 0 || newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                            sensor.connectInFlight = false
                        }
                    }
                )

                scope.launch {
                    delay(CONNECT_ATTEMPT_STALE_MS)
                    if (serviceActive && !sensor.connected && sensor.connectInFlight) {
                        sensor.connectInFlight = false
                        if (DEBUG_BLE) {
                            Log.d(
                                "SYNCROW",
                                "Connect attempt stale -> retry loop mac=${bleMacForLog(sensor.mac)}"
                            )
                        }
                        startReconnectLoop(sensor)
                    }
                }
            }
        }
    }

    private var samplingJob: Job? = null

    private fun startSamplingLoopIfNeeded() {
        if (samplingJob?.isActive == true) return
        samplingJob = scope.launch {
            var nextTickMs = SystemClock.elapsedRealtime()
            while (serviceActive) {
                if (!intervalRunning) {
                    delay(50L)
                    nextTickMs = SystemClock.elapsedRealtime()
                    continue
                }

                val nowElapsed = SystemClock.elapsedRealtime()
                if (nowElapsed < nextTickMs) {
                    delay(nextTickMs - nowElapsed)
                    continue
                }

                val nowWall = System.currentTimeMillis()
                val locationSnapshot = latestLocation
                if (locationSnapshot != null) {
                    addLocationSampleDeduped(locationSnapshot.copy(timestampMs = nowWall))
                }
                activeSensors.values.toList().forEach { sensor ->
                    val latest = sensor.latestSample ?: return@forEach
                    if (!sensor.connected) return@forEach
                    if (sensor.latestSampleMs < intervalStartWallMs) return@forEach

                    sensor.samples.add(
                        SensorSample(
                            timestampMs = nowWall,
                            ax = latest.ax,
                            ay = latest.ay,
                            az = latest.az,
                            wx = latest.wx,
                            wy = latest.wy,
                            wz = latest.wz,
                            roll = latest.roll,
                            pitch = latest.pitch,
                            yaw = latest.yaw
                        )
                    )
                    sensor.lastStoredSampleMs = nowWall
                    updateStrokeDetector(sensor, nowWall, latest.ax, latest.ay, latest.az)

                    // Fresh iff a new BLE sample arrived since we last fed this sensor.
                    // If not, latestSample is a zero-order-hold repeat — the analyzer
                    // skips it and, if held too long, marks the seat DEGRADED_SIGNAL.
                    val fresh = sensor.latestSampleMs > sensor.lastFedBleMs
                    sensor.lastFedBleMs = sensor.latestSampleMs

                    // Feed to stroke analyzer for catch detection & lateness. The
                    // return is only non-null on the exact catch+pair tick; the
                    // display uses the analyzer's STORED current offset instead.
                    strokeAnalyzer.onSample(
                        sensor.mac, nowWall, fresh,
                        latest.pitch, latest.roll, latest.yaw,
                        latest.wx, latest.wy, latest.wz
                    )
                    val syncStatus = strokeAnalyzer.getStatus(sensor.mac)

                    val prefsEdit = prefs.edit()
                        .putString(statusKey(sensor.mac), "RECORDING")
                        .putInt(strokesKey(sensor.mac), sensor.strokeCount)
                        .putInt(spmKey(sensor.mac), sensor.currentSpm)
                        .putBoolean(connectedKey(sensor.mac), true)
                    // Per-seat gating (rowing seats only; cox has no lateness). Only a
                    // trustworthy (OK) seat shows a lateness; a degraded/stale seat is
                    // cleared to "--" so we never display a frozen number — and no other
                    // seat is affected.
                    if (sensor.role == SensorRole.SEAT) {
                        prefsEdit.putString(syncStatusKey(sensor.mac), syncStatus.name)
                        // Show the analyzer's stored current offset whenever the seat is
                        // OK (it's refreshed whenever this seat OR the stroke seat catches),
                        // so an OK seat always displays its latest lateness. Blank only when
                        // there's no trustworthy value.
                        val current = strokeAnalyzer.getLateness(sensor.mac)
                        if (syncStatus == SensorSyncStatus.OK && current != null) {
                            prefsEdit.putLong(latenessKey(sensor.mac), current)
                        } else {
                            prefsEdit.putLong(latenessKey(sensor.mac), Long.MIN_VALUE)
                        }
                    }
                    prefsEdit.apply()
                }

                nextTickMs += targetSamplePeriodMs
            }
        }
    }

    private fun stopSamplingLoop() {
        samplingJob?.cancel()
        samplingJob = null
    }

    private var diagnosticsJob: Job? = null

    private fun startDiagnosticsLoopIfNeeded() {
        if (diagnosticsJob?.isActive == true) return
        diagnosticsJob = scope.launch {
            val rssiPeriodMs = 5_000L           // RSSI every 5s; polling at 1 Hz × N sensors competes with sample events.
            val maxCleanWindowMs = 5_000L       // beyond this (Doze, GC pause), drop the tick — faking a 1s window lies.
            var lastRssiReadElapsedMs = 0L
            var prevTickElapsedMs = 0L
            while (serviceActive) {
                // Snap ticks to whole-second wall-clock boundaries so multi-sensor rows
                // for the same second land on the same InfluxDB timestamp.
                val nowWall = System.currentTimeMillis()
                val nextTickWall = ((nowWall / 1000L) + 1L) * 1000L
                delay((nextTickWall - nowWall).coerceAtLeast(0L))
                if (!serviceActive) break

                try {
                    runDiagnosticsTick(
                        prevTickElapsedMs = prevTickElapsedMs,
                        maxCleanWindowMs = maxCleanWindowMs,
                        rssiPeriodMs = rssiPeriodMs,
                        lastRssiReadElapsedMs = lastRssiReadElapsedMs
                    ).let { result ->
                        prevTickElapsedMs = result.newPrevTickElapsedMs
                        lastRssiReadElapsedMs = result.newLastRssiReadElapsedMs
                    }
                } catch (e: Throwable) {
                    // Never let a stray exception kill the loop. Reset prev tick so the next
                    // row is flagged as a gap rather than compared against a stale baseline.
                    Log.e("SYNCROW", "Diagnostics tick threw: ${e.javaClass.simpleName}: ${e.message}", e)
                    prevTickElapsedMs = 0L
                }
            }
            diagnosticsJob = null
        }
    }

    private data class TickResult(
        val newPrevTickElapsedMs: Long,
        val newLastRssiReadElapsedMs: Long
    )

    private fun runDiagnosticsTick(
        prevTickElapsedMs: Long,
        maxCleanWindowMs: Long,
        rssiPeriodMs: Long,
        lastRssiReadElapsedMs: Long
    ): TickResult {
        val tickWallMs = System.currentTimeMillis()
        val tickElapsedMs = SystemClock.elapsedRealtime()

        // Actual window length since the previous tick. If the coroutine was paused
        // (Doze, long GC, etc.) for longer than maxCleanWindowMs, emitting a row would
        // produce a wildly inaccurate drop% — reset the baseline and skip this tick.
        val rawDeltaMs = if (prevTickElapsedMs == 0L) 1000L else tickElapsedMs - prevTickElapsedMs
        if (rawDeltaMs > maxCleanWindowMs) {
            Log.w("SYNCROW", "Diagnostics tick gap ${rawDeltaMs}ms — dropping row, resetting baseline")
            return TickResult(
                newPrevTickElapsedMs = tickElapsedMs,
                newLastRssiReadElapsedMs = lastRssiReadElapsedMs
            )
        }
        val windowDurationMs = rawDeltaMs

        val expectedHzWhole = BleDeviceClient.expectedHzFor(BleDeviceClient.targetRateRegister)
        // Round instead of truncate: 100 Hz × 999 ms = 99.9 should report expected=100, not 99.
        val expectedThisWindow = Math.round(expectedHzWhole * windowDurationMs / 1000.0).toInt()

        val captureIntervalRunning = intervalRunning
        val captureSessionId = if (captureIntervalRunning) sessionIntervalId else -1L

        val sensors = activeSensors.values.toList()

        val newLastRssiReadElapsedMs = if (tickElapsedMs - lastRssiReadElapsedMs >= rssiPeriodMs) {
            sensors.forEach { if (it.connected) it.client.requestRssiRead() }
            tickElapsedMs
        } else lastRssiReadElapsedMs

        // Build rows with per-sensor isolation — one misbehaving sensor must not nuke
        // the whole tick. Failed sensors drop out of this tick's output silently; the
        // catch will log once per failure.
        val rows = sensors.mapNotNull { s ->
            try {
                buildDiagnosticRow(s, tickWallMs, tickElapsedMs, windowDurationMs,
                    expectedThisWindow, captureIntervalRunning, captureSessionId)
            } catch (e: Throwable) {
                Log.e("SYNCROW", "Diagnostic row build failed mac=${bleMacForLog(s.mac)}: ${e.message}", e)
                null
            }
        }

        // Update the existing hzKey prefs so LiveRow/ManageSensors status still works.
        // Skip disconnected sensors — writing 0 Hz every second is noise, and the disconnect
        // paths already zero hzKey via the health monitor.
        if (rows.isNotEmpty()) {
            val editor = prefs.edit()
            var dirty = false
            rows.forEach { r ->
                if (r.connected) {
                    editor.putFloat(hzKey(r.sensorMac), r.received.toFloat())
                    dirty = true
                }
            }
            if (dirty) editor.apply()
        }

        try {
            DiagnosticsStore.writeLatest(this@IntervalRecordingService, rows)
        } catch (e: Exception) {
            // Promote to unconditional ERROR — in release builds a full disk or permissions
            // regression would otherwise silently stall the diag screen with stale data.
            Log.e("SYNCROW", "DiagnosticsStore.writeLatest failed: ${e.message}", e)
        }

        if (captureIntervalRunning && captureSessionId != -1L && rows.isNotEmpty()) {
            try {
                DiagnosticsStore.appendInterval(
                    this@IntervalRecordingService,
                    captureSessionId,
                    rows
                )
            } catch (e: Exception) {
                Log.e("SYNCROW", "DiagnosticsStore.appendInterval failed: ${e.message}", e)
            }
        }

        return TickResult(
            newPrevTickElapsedMs = tickElapsedMs,
            newLastRssiReadElapsedMs = newLastRssiReadElapsedMs
        )
    }

    private fun buildDiagnosticRow(
        s: ActiveSensor,
        tickWallMs: Long,
        tickElapsedMs: Long,
        windowDurationMs: Long,
        expectedThisWindow: Int,
        captureIntervalRunning: Boolean,
        captureSessionId: Long
    ): SensorDiagnostic {
        val snap = s.client.snapshotAndReset()
        val received = snap.received
        // Signed drop %: negative means surplus. Preserve the sign so misconfigured rates
        // surface instead of being silently clamped to zero.
        val dropPct = if (expectedThisWindow > 0) {
            (expectedThisWindow - received) * 100.0 / expectedThisWindow
        } else 0.0
        val reconnects = s.reconnectsThisWindow.getAndSet(0)

        return SensorDiagnostic(
            timestampMs = tickWallMs,
            elapsedMs = tickElapsedMs,
            windowDurationMs = windowDurationMs,
            sensorMac = s.mac,
            sensorId = if (captureIntervalRunning) s.id else null,
            seat = s.seat,
            intervalId = captureSessionId,
            expected = expectedThisWindow,
            received = received,
            dropPct = dropPct,
            maxGapMs = snap.maxGapMs,
            jitterMs = snap.jitterMs,
            malformed = snap.malformed,
            rssi = snap.rssi,
            connected = s.connected,
            configApplied = snap.configApplied,
            configFailed = snap.configFailed,
            reconnectsThisWindow = reconnects,
            lastGattStatus = snap.lastGattStatus,
            connectionIntervalMs = snap.connectionIntervalMs,
            timeReceived = snap.timeReceived
        )
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdatesIfNeeded() {
        if (!hasLocationPermission()) return
        if (locationListener != null) return

        val mgr = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = mgr
        val lastKnown = pickBestLastKnownLocation(mgr)
        latestLocation = lastKnown?.toSample()
        if (lastKnown != null && intervalRunning) {
            addLocationSampleDeduped(latestLocation!!)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val sample = location.toSample()
                latestLocation = sample
                if (intervalRunning) {
                    addLocationSampleDeduped(sample)
                }
            }
        }
        locationListener = listener

        try {
            Log.e("SYNCROW", "Location updates: gps=${mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)} network=${mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")
            mgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
            mgr.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("SYNCROW", "Location updates failed: ${e.message}", e)
        }
    }

    private fun pickBestLastKnownLocation(mgr: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        providers.forEach { provider ->
            try {
                val loc = mgr.getLastKnownLocation(provider) ?: return@forEach
                if (best == null || loc.time > best!!.time) {
                    best = loc
                }
            } catch (_: Exception) {
            }
        }
        return best
    }

    private fun stopLocationUpdates() {
        val mgr = locationManager ?: return
        val listener = locationListener ?: return
        try {
            mgr.removeUpdates(listener)
        } catch (_: Exception) {
        } finally {
            locationListener = null
            locationManager = null
        }
    }

    private fun Location.toSample(): LocationSample {
        return LocationSample(
            timestampMs = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy else null,
            speed = if (hasSpeed()) speed else null,
            bearing = if (hasBearing()) bearing else null
        )
    }

    private fun startHealthMonitorIfNeeded() {
        if (healthMonitorActive) return
        healthMonitorActive = true

        scope.launch {
            val timeoutMs = 2_000L
            var lastPriorityReassertMs = 0L
            val priorityReassertIntervalMs = 5_000L
            while (serviceActive) {
                delay(500L)
                val now = System.currentTimeMillis()
                activeSensors.values.toList().forEach { sensor ->
                    if (!sensor.connected) return@forEach
                    val lastSeen = sensor.lastSeenSampleMs
                    if (lastSeen == 0L) return@forEach
                    if (now - lastSeen <= timeoutMs) return@forEach

                    sensor.connected = false
                    prefs.edit()
                        .putString(statusKey(sensor.mac), "DISCONNECTED (reconnecting…)")
                        .putBoolean(connectedKey(sensor.mac), false)
                        .putFloat(hzKey(sensor.mac), 0f)
                        .apply()
                    setOngoingNotification("DISCONNECTED • reconnecting…")
                    maybeNotifySensorDisconnected(sensor)
                    startReconnectLoop(sensor)
                }

                // Android silently drops back to BALANCED connection priority after idle / low
                // battery. Re-assert HIGH every 5s on connected sensors to hold the short
                // connection interval that gives us 100+ Hz headroom.
                if (now - lastPriorityReassertMs >= priorityReassertIntervalMs) {
                    lastPriorityReassertMs = now
                    activeSensors.values.toList().forEach { sensor ->
                        if (sensor.connected) sensor.client.reassertConnectionPriority()
                    }
                }
            }
            healthMonitorActive = false
        }
    }

    private fun maybeNotifySensorDisconnected(sensor: ActiveSensor) {
        if (!intervalRunning) return

        // Avoid spamming: at most once per sensor per 15 seconds.
        val now = System.currentTimeMillis()
        val last = sensor.lastDisconnectNotifMs
        if (last != 0L && (now - last) < 15_000L) return
        sensor.lastDisconnectNotifMs = now

        postEventNotification(
            "Sensor disconnected",
            "${sensor.displayName} disconnected. Reconnecting…"
        )
    }

    private fun startReconnectLoop(sensor: ActiveSensor) {
        if (sensor.reconnectJobActive) return
        sensor.reconnectJobActive = true

        sensor.reconnectJob = scope.launch {
            var attempt = 0
            while (serviceActive && !sensor.connected) {
                attempt++
                val delayMs = reconnectDelayMs(attempt, sensor.timeout147Count)
                delay(delayMs)
                if (!serviceActive || sensor.connected) break
                if (sensor.connectInFlight) continue
                try { connectSensor(sensor, "reconnect_attempt_$attempt") } catch (_: Exception) {}
            }
            sensor.reconnectJobActive = false
            sensor.reconnectJob = null
            setUiStatusIfIdle()
        }
    }

    private fun reconnectDelayMs(attempt: Int, timeout147Count: Int): Long {
        val base = attempt.coerceAtMost(6) * 1_000L
        val timeoutPenalty = timeout147Count.coerceAtMost(6) * 1_500L
        return (base + timeoutPenalty).coerceAtMost(20_000L)
    }

    private fun logResumeDiagnostics(
        lastForegroundElapsedMs: Long,
        elapsedSinceForegroundMs: Long,
        lastBackgroundElapsedMs: Long,
        elapsedSinceBackgroundMs: Long
    ) {
        if (!DEBUG_BLE) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val idle = pm?.isDeviceIdleMode ?: false
        val ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        val standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                usm?.appStandbyBucket?.toString() ?: "unknown"
            } catch (_: Exception) {
                "unavailable"
            }
        } else {
            "n/a"
        }
        val states = activeSensors.values.sortedByDescending { it.seatIndex }.joinToString("; ") { sensor ->
            val state = when {
                sensor.connected -> "READY"
                sensor.connectInFlight -> "CONNECTING"
                else -> "DISCONNECTED"
            }
            "${bleMacForLog(sensor.mac)}:$state(retryJob=${sensor.reconnectJobActive},147=${sensor.timeout147Count})"
        }
        val lostRetryCount = activeSensors.values.count { !it.connected && !it.connectInFlight && !it.reconnectJobActive }
        Log.d(
            "SYNCROW",
            "Resume diag: processStartElapsedMs=$processStartElapsedMs " +
                "lastForegroundElapsedMs=$lastForegroundElapsedMs elapsedSinceForegroundMs=$elapsedSinceForegroundMs " +
                "lastBackgroundElapsedMs=$lastBackgroundElapsedMs elapsedSinceBackgroundMs=$elapsedSinceBackgroundMs " +
                "deviceIdle=$idle ignoringBatteryOptimizations=$ignoringBatteryOptimizations standbyBucket=$standbyBucket " +
                "activeSensors=${activeSensors.size} lostRetryCount=$lostRetryCount states=[$states]"
        )
    }

    private fun maybeRunResumeSoftReset(elapsedSinceBackgroundMs: Long) {
        if (!serviceActive) return
        if (softResetInProgress) return

        val hasNonReady = activeSensors.values.any { !it.connected }
        val longIdle = elapsedSinceBackgroundMs < 0L || elapsedSinceBackgroundMs > RESUME_SELF_HEAL_THRESHOLD_MS
        if (!longIdle && !hasNonReady) return

        val reason = if (longIdle) {
            "elapsedSinceBackgroundMs=$elapsedSinceBackgroundMs"
        } else {
            "nonReadySensors=${activeSensors.values.count { !it.connected }}"
        }
        if (intervalRunning) {
            pendingResumeSoftReset = true
            pendingResumeSoftResetReason = reason
            persistPendingSoftReset(reason)
            if (DEBUG_BLE) {
                Log.d("SYNCROW", "Soft BLE reset deferred during active interval: reason=$reason")
            }
            return
        }
        scope.launch { runSoftBleResetAndReconnect(reason) }
    }

    private fun maybeRunPersistedSoftResetIfNeeded(): Boolean {
        if (intervalRunning || softResetInProgress) return false
        val pending = prefs.getBoolean(KEY_PENDING_SOFT_RESET, false)
        if (!pending) return false
        val reason = prefs.getString(KEY_PENDING_SOFT_RESET_REASON, null) ?: "persisted"
        clearPendingSoftReset()
        pendingResumeSoftReset = false
        pendingResumeSoftResetReason = null
        scope.launch { runSoftBleResetAndReconnect("persisted:$reason") }
        return true
    }

    private fun maybeRunDeferredSoftResetAfterStop() {
        if (!pendingResumeSoftReset) return
        if (intervalRunning) return
        val reason = pendingResumeSoftResetReason ?: "deferred"
        pendingResumeSoftReset = false
        pendingResumeSoftResetReason = null
        clearPendingSoftReset()
        scope.launch { runSoftBleResetAndReconnect("deferred_after_stop:$reason") }
    }

    private fun persistPendingSoftReset(reason: String) {
        prefs.edit()
            .putBoolean(KEY_PENDING_SOFT_RESET, true)
            .putString(KEY_PENDING_SOFT_RESET_REASON, reason)
            .apply()
    }

    private fun clearPendingSoftReset() {
        prefs.edit()
            .putBoolean(KEY_PENDING_SOFT_RESET, false)
            .remove(KEY_PENDING_SOFT_RESET_REASON)
            .apply()
    }

    private suspend fun runSoftBleResetAndReconnect(reason: String) {
        if (softResetInProgress) return
        if (intervalRunning) return
        softResetInProgress = true
        try {
            if (DEBUG_BLE) {
                Log.d("SYNCROW", "Soft BLE reset start: reason=$reason activeSensors=${activeSensors.size}")
                Log.d("SYNCROW", "Soft BLE reset: stop scan requested (no active scanner in service)")
            }
            val snapshot = activeSensors.values.sortedByDescending { it.seatIndex }.toList()
            snapshot.forEach { sensor ->
                sensor.reconnectJob?.cancel()
                sensor.reconnectJob = null
                sensor.reconnectJobActive = false
                sensor.connectInFlight = false
                sensor.connected = false
                sensor.timeout147Count = 0
                prefs.edit()
                    .putString(statusKey(sensor.mac), "DISCONNECTED (reconnecting…)")
                    .putBoolean(connectedKey(sensor.mac), false)
                    .apply()
                try { sensor.client.disconnect() } catch (_: Exception) {}
            }

            delay(SOFT_RESET_RECONNECT_DELAY_MS)

            snapshot.forEachIndexed { index, sensor ->
                if (!serviceActive) return@forEachIndexed
                connectSensor(sensor, "resume_soft_reset_${index + 1}")
                delay(400L)
            }
            snapshot.forEach { sensor ->
                if (!sensor.connected) startReconnectLoop(sensor)
            }
            if (DEBUG_BLE) Log.d("SYNCROW", "Soft BLE reset queued reconnects")
        } finally {
            softResetInProgress = false
        }
    }

    private fun updateStrokeDetector(sensor: ActiveSensor, nowMs: Long, ax: Float, ay: Float, az: Float) {
        val dominant = listOf(ax, ay, az).maxByOrNull { kotlin.math.abs(it) } ?: 0f
        val v = when {
            dominant > accelDeadband -> dominant
            dominant < -accelDeadband -> dominant
            else -> 0f
        }

        val newState = when {
            v > 0f -> StrokeState.FORWARD
            v < 0f -> StrokeState.BACKWARD
            else -> sensor.detectorState
        }

        if (sensor.detectorState == StrokeState.UNKNOWN) {
            sensor.detectorState = newState
            sensor.stateStartMs = nowMs
            return
        }

        if (newState == sensor.detectorState) return

        val phaseDuration = nowMs - sensor.stateStartMs
        if (phaseDuration < minPhaseMs) return

        if (sensor.detectorState == StrokeState.BACKWARD && newState == StrokeState.FORWARD) {
            sensor.strokeCount++
            sensor.strokeTimesMs.addLast(nowMs)
            while (sensor.strokeTimesMs.size > 30) sensor.strokeTimesMs.removeFirst()
            sensor.currentSpm = computeSpm(sensor)
            if (sensor.currentSpm > sensor.maxSpm) sensor.maxSpm = sensor.currentSpm
        }

        sensor.detectorState = newState
        sensor.stateStartMs = nowMs
    }

    private fun computeSpm(sensor: ActiveSensor): Int {
        if (sensor.strokeTimesMs.size < 2) return 0

        val now = System.currentTimeMillis()
        val windowMs = 7_000L
        while (sensor.strokeTimesMs.isNotEmpty() && (now - sensor.strokeTimesMs.first()) > windowMs) {
            sensor.strokeTimesMs.removeFirst()
        }
        if (sensor.strokeTimesMs.size < 2) return 0

        val first = sensor.strokeTimesMs.first()
        val last = sensor.strokeTimesMs.last()
        val dt = last - first
        if (dt <= 0) return 0
        val strokes = sensor.strokeTimesMs.size - 1
        return (strokes * 60_000.0 / dt).toInt().coerceIn(0, 120)
    }

    private fun normalizeBySeat(
        seatIndex: Int,
        ax: Float,
        ay: Float,
        az: Float,
        wx: Float,
        wy: Float,
        wz: Float,
        roll: Float,
        pitch: Float,
        yaw: Float
    ): Normalized {
        // All oars are mounted the same, so no seat-specific rotation.
        return Normalized(ax, ay, az, wx, wy, wz, roll, pitch, yaw)
    }

    private data class Normalized(
        val ax: Float,
        val ay: Float,
        val az: Float,
        val wx: Float,
        val wy: Float,
        val wz: Float,
        val roll: Float,
        val pitch: Float,
        val yaw: Float
    )

    private data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
        operator fun times(o: Quat): Quat =
            Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z
            )
    }

    private fun eulerToQuat(rollDeg: Float, pitchDeg: Float, yawDeg: Float): Quat {
        val r = rollDeg.toRad() * 0.5f
        val p = pitchDeg.toRad() * 0.5f
        val y = yawDeg.toRad() * 0.5f

        val sr = sin(r); val cr = cos(r)
        val sp = sin(p); val cp = cos(p)
        val sy = sin(y); val cy = cos(y)

        return Quat(
            x = sr * cp * cy - cr * sp * sy,
            y = cr * sp * cy + sr * cp * sy,
            z = cr * cp * sy - sr * sp * cy,
            w = cr * cp * cy + sr * sp * sy
        )
    }

    private fun quatToEuler(q: Quat): Triple<Float, Float, Float> {
        // roll (x-axis rotation)
        val sinr_cosp = 2f * (q.w * q.x + q.y * q.z)
        val cosr_cosp = 1f - 2f * (q.x * q.x + q.y * q.y)
        val roll = atan2(sinr_cosp, cosr_cosp)

        // pitch (y-axis rotation)
        val sinp = 2f * (q.w * q.y - q.z * q.x)
        val pitch = if (kotlin.math.abs(sinp) >= 1f) {
            kotlin.math.sign(sinp) * (Math.PI.toFloat() / 2f)
        } else {
            kotlin.math.asin(sinp)
        }

        // yaw (z-axis rotation)
        val siny_cosp = 2f * (q.w * q.z + q.x * q.y)
        val cosy_cosp = 1f - 2f * (q.y * q.y + q.z * q.z)
        val yaw = atan2(siny_cosp, cosy_cosp)

        return Triple(roll.toDeg(), pitch.toDeg(), yaw.toDeg())
    }

    private fun Float.toRad(): Float = (this / 180f * Math.PI).toFloat()
    private fun Float.toDeg(): Float = (this * 180f / Math.PI.toFloat())

    private fun saveIntervalLocally(payload: IntervalUpload): File {
        val dir = File(filesDir, "intervals")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "interval_${payload.interval_id}.json")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        file.writeText(moshi.adapter(IntervalUpload::class.java).toJson(payload))
        return file
    }

    private fun saveIntervalLocally(payload: IntervalUpload, sensorId: String): File {
        val dir = File(filesDir, "intervals")
        if (!dir.exists()) dir.mkdirs()
        val safeSensorId = sensorId.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").replace(" ", "_")
        val file = File(dir, "interval_${payload.interval_id}_${safeSensorId}.json")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        file.writeText(moshi.adapter(IntervalUpload::class.java).toJson(payload))
        return file
    }

    private fun saveLocationLocally(payload: LocationUpload): File {
        val dir = File(filesDir, "intervals")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "location_${payload.interval_id}.json")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        file.writeText(moshi.adapter(LocationUpload::class.java).toJson(payload))
        return file
    }

    private fun saveSessionSummary(sessionId: Long, durationMs: Long) {
        val dir = File(filesDir, "intervals")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "summary_${sessionId}.json")

        val seats = activeSensors.values
            .filter { it.role == SensorRole.SEAT } // Cox has no strokes; excluded from the per-seat recap.
            .sortedByDescending { it.seatIndex } // Seat 1 is bottom (smallest index)
            .map { sensor ->
                val avgSpm = if (durationMs > 0) {
                    ((sensor.strokeCount * 60_000.0) / durationMs).toInt().coerceIn(0, 120)
                } else 0
                SeatSummary(
                    seatIndex = sensor.seatIndex,
                    displayName = sensor.displayName,
                    avgSpm = avgSpm,
                    maxSpm = sensor.maxSpm.coerceIn(0, 120),
                    strokes = sensor.strokeCount
                )
            }

        val summary = SessionSummary(
            intervalId = sessionId,
            durationMs = durationMs,
            seats = seats
        )

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        file.writeText(moshi.adapter(SessionSummary::class.java).toJson(summary))
    }

    private fun setOngoingNotification(status: String) {
        lastNotifStatus = status
        Notifications.ensureChannels(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ONGOING_NOTIF_ID, buildOngoingNotification(status))
    }

    private fun buildOngoingNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, IntervalRecordingService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, Notifications.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SyncRow")
            .setContentText(status)
            .setContentIntent(openAppPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postEventNotification(title: String, message: String) {
        Notifications.ensureChannels(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    private fun hasInternetNow(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                uploadPendingIntervalsIfNeeded()
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e("SYNCROW", "Failed to register network callback: ${e.message}", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            networkCallback = null
        }
    }

    private fun uploadPendingIntervalsIfNeeded() {
        if (!pendingUploadActive.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!hasInternetNow()) return@launch

                val pending = IntervalIndexStore.load(this@IntervalRecordingService)
                    .filter { it.syncStatus == SyncStatus.SYNCING || it.syncStatus == SyncStatus.PENDING }
                if (pending.isEmpty()) return@launch

                pending.forEach { meta ->
                    val payloads = loadIntervalPayloads(meta.id)
                    val locationPayload = loadLocationPayload(meta.id)
                    if (payloads.isEmpty() && locationPayload == null) return@forEach

                    var anyFailed = false
                    var anyRetryable = false
                    val intervalLabel = IntervalNamesStore.get(this@IntervalRecordingService, meta.id)
                        ?: "Interval_${meta.id}"

                    payloads.forEach { payload ->
                        val (success, code) = try {
                            ApiClient.uploadInterval(payload, intervalLabel)
                        } catch (e: Exception) {
                            Log.e("SYNCROW", "Deferred upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
                            Pair(false, -1)
                        }
                        if (!success) {
                            if (code == 429) {
                                anyRetryable = true
                                return@forEach
                            } else {
                                anyFailed = true
                            }
                        }
                    }
                    if (anyRetryable) {
                        // Stop this batch when rate limited; retry on a later pass.
                        return@forEach
                    }

                    if (locationPayload != null) {
                        val (success, code) = try {
                            ApiClient.uploadLocation(locationPayload, intervalLabel)
                        } catch (e: Exception) {
                            Log.e("SYNCROW", "Deferred location upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
                            Pair(false, -1)
                        }
                        if (success) {
                            IntervalIndexStore.updateLocationStatus(
                                this@IntervalRecordingService,
                                meta.id,
                                SyncStatus.SYNCED
                            )
                        } else if (code == 429) {
                            anyRetryable = true
                            IntervalIndexStore.updateLocationStatus(
                                this@IntervalRecordingService,
                                meta.id,
                                SyncStatus.SYNCING
                            )
                            // Stop processing additional uploads this pass when rate limited.
                            anyFailed = false
                        } else {
                            anyFailed = true
                            IntervalIndexStore.updateLocationStatus(
                                this@IntervalRecordingService,
                                meta.id,
                                SyncStatus.FAILED
                            )
                        }
                    }

                    // Diagnostics upload — lower priority: only retry if IMU/location didn't hit
                    // rate-limit this pass, and only if the diag file exists and isn't already SYNCED.
                    if (!anyRetryable &&
                        meta.diagSyncStatus != null &&
                        meta.diagSyncStatus != SyncStatus.SYNCED
                    ) {
                        val diagRows = try {
                            DiagnosticsStore.readInterval(this@IntervalRecordingService, meta.id)
                        } catch (e: Exception) {
                            Log.e("SYNCROW", "Diag read failed: ${e.message}", e)
                            emptyList()
                        }
                        if (diagRows.isEmpty()) {
                            // File missing/empty — mark synced so we stop retrying.
                            IntervalIndexStore.updateDiagStatus(
                                this@IntervalRecordingService,
                                meta.id,
                                SyncStatus.SYNCED
                            )
                        } else {
                            val (success, code) = try {
                                ApiClient.uploadDiagnostics(diagRows, intervalLabel)
                            } catch (e: Exception) {
                                Log.e("SYNCROW", "Deferred diag upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
                                Pair(false, -1)
                            }
                            if (success) {
                                IntervalIndexStore.updateDiagStatus(
                                    this@IntervalRecordingService,
                                    meta.id,
                                    SyncStatus.SYNCED
                                )
                            } else if (code == 429) {
                                anyRetryable = true
                                IntervalIndexStore.updateDiagStatus(
                                    this@IntervalRecordingService,
                                    meta.id,
                                    SyncStatus.SYNCING
                                )
                            } else {
                                // Don't fail the whole interval over diagnostics.
                                IntervalIndexStore.updateDiagStatus(
                                    this@IntervalRecordingService,
                                    meta.id,
                                    SyncStatus.FAILED
                                )
                            }
                        }
                    }

                    if (!anyFailed && !anyRetryable) {
                        IntervalIndexStore.updateStatus(
                            this@IntervalRecordingService,
                            meta.id,
                            SyncStatus.SYNCED
                        )
                    } else if (anyRetryable && !anyFailed) {
                        IntervalIndexStore.updateStatus(
                            this@IntervalRecordingService,
                            meta.id,
                            SyncStatus.SYNCING
                        )
                    } else {
                        // Keep SYNCING so a future connectivity change can trigger another attempt.
                        IntervalIndexStore.updateStatus(
                            this@IntervalRecordingService,
                            meta.id,
                            SyncStatus.SYNCING
                        )
                    }
                }
            } finally {
                pendingUploadActive.set(false)
            }
        }
    }

    private fun loadIntervalPayloads(intervalId: Long): List<IntervalUpload> {
        val dir = File(filesDir, "intervals")
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f ->
            val name = f.name
            name == "interval_${intervalId}.json" ||
                (name.startsWith("interval_${intervalId}_") && name.endsWith(".json"))
        } ?: return emptyList()

        if (files.isEmpty()) return emptyList()

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(IntervalUpload::class.java)

        return files.mapNotNull { f ->
            try {
                adapter.fromJson(f.readText())
            } catch (e: Exception) {
                Log.e("SYNCROW", "Failed to read interval payload ${f.name}: ${e.message}", e)
                null
            }
        }
    }

    private fun loadLocationPayload(intervalId: Long): LocationUpload? {
        val f = File(File(filesDir, "intervals"), "location_${intervalId}.json")
        if (!f.exists()) return null

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(LocationUpload::class.java)

        return try {
            adapter.fromJson(f.readText())
        } catch (e: Exception) {
            Log.e("SYNCROW", "Failed to read location payload ${f.name}: ${e.message}", e)
            null
        }
    }

    override fun onDestroy() {
        activeSensors.values.forEach { sensor ->
            try { sensor.client.disconnect() } catch (_: Exception) {}
        }
        activeSensors.clear()
        unregisterNetworkCallback()
        stopSamplingLoop()
        stopLocationUpdates()
        scope.cancel()
        super.onDestroy()
    }

    private fun canStartConnectedDeviceFgs(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleMissingBlePermission() {
        intervalRunning = false
        serviceActive = false

        prefs.edit()
            .putBoolean(KEY_INTERVAL_RUNNING, false)
            .putBoolean(KEY_SERVICE_ACTIVE, false)
            .apply()

        postEventNotification(
            "Bluetooth permission required",
            "Enable Bluetooth permission (BLUETOOTH_CONNECT) to start an interval."
        )
    }
}
