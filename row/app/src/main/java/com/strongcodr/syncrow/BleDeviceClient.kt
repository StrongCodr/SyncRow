package com.strongcodr.syncrow

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.sqrt

data class BleDiagnosticSnapshot(
    val received: Int,
    val malformed: Int,
    val maxGapMs: Long,
    val jitterMs: Double,
    val rssi: Int?,
    val lastGattStatus: Int?,
    val configApplied: Boolean,
    val configFailed: Boolean,
    /** Negotiated link-layer connection interval in milliseconds. Currently always
     *  null (dormant): the only source, BluetoothGattCallback.onConnectionUpdated, is
     *  a hidden/@SystemApi method a normal app can't override. Kept in the schema so
     *  it renders as "—" and can be re-enabled if a public API appears. */
    val connectionIntervalMs: Double?,
    /** Count of 0x55 0x50 TIME packets received this window. Zero when the time-packet
     *  toggle is off (RSW bit 0 not set). Used to verify whether enabling TIME
     *  significantly cuts effective 0x61 throughput on a saturated link. */
    val timeReceived: Int
)

class BleDeviceClient(private val context: Context) {

    private val tag = "BleDeviceClient"

    private var gatt: BluetoothGatt? = null
    private var sampleCallback: ((Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    private var disconnectCallback: (() -> Unit)? = null

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingWrites: ArrayDeque<ByteArray> = ArrayDeque()
    private var writeInFlight = false

    // Per-window diagnostic counters. Mutated on the BLE binder thread inside
    // onCharacteristicChanged and the various GATT callbacks; read+reset from the
    // service tick on an IO coroutine via snapshotAndReset(). All access is serialized
    // through @Synchronized on `this` for a single consistent memory model.
    private var received: Int = 0
    private var malformed: Int = 0
    private var maxGapMs: Long = 0L
    private var intervalN: Int = 0                 // count of inter-sample gaps seen
    private var intervalSum: Double = 0.0
    private var intervalSumSq: Double = 0.0
    private var lastSampleElapsedMs: Long = 0L
    private var lastRssi: Int? = null
    private var lastGattStatus: Int? = null
    private var lastConnectionIntervalUnits: Int? = null   // 1.25 ms units; always null (dormant — see onConnectionUpdated note)
    private var timeReceived: Int = 0                      // count of 0x55 0x50 packets seen this window

    // Config-write sequence tracking. configFailed flips true on any non-success write;
    // configApplied is true only once all three frames (unlock/rate/save) have succeeded.
    private var configPendingCount: Int = 0
    private var configFailed: Boolean = false
    private var configApplied: Boolean = false

    companion object {
        // WitMotion WT9011DCL canonical BLE UUIDs. Note: the base ends in "9a34fb"
        // (not the BT SIG standard "9b34fb") — firmware quirk, preserve exactly.
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
        val NOTIFY_UUID:  UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb")
        val WRITE_UUID:   UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9a34fb")
        // Standard CCCD descriptor (uses "9b34fb" — this is the real BT SIG UUID).
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // WIT protocol config frames: 5-byte [FF AA <reg> <lo> <hi>]
        private val CMD_UNLOCK = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69.toByte(), 0x88.toByte(), 0xB5.toByte())
        private val CMD_SAVE   = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        private fun cmdSetRate(rrate: Int) = byteArrayOf(
            0xFF.toByte(), 0xAA.toByte(), 0x03.toByte(), rrate.toByte(), 0x00.toByte()
        )

        // RSW (Output Selection) register bitmask. On the BLE 5.0 firmware family, the
        // 0x61 combined packet streams automatically regardless of RSW. Bit 0 controls the
        // separate TIME (0x50) stream; we use this to enable/disable just that.
        private fun cmdSetRsw(value: Int) = byteArrayOf(
            0xFF.toByte(), 0xAA.toByte(), 0x02.toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
        private const val RSW_TIME_BIT = 0x0001
        private const val RSW_OFF      = 0x0000

        // RRATE register values (0x03). See wit_sensor_protocol memory.
        const val RRATE_10HZ  = 0x06
        const val RRATE_20HZ  = 0x07
        const val RRATE_50HZ  = 0x08
        const val RRATE_100HZ = 0x09
        const val RRATE_200HZ = 0x0B

        // App-wide target ODR. Mutable so the recording path can raise/lower without rebuilding.
        // Default is 50 Hz: the honest per-sensor ceiling for an 8+1 (nine sensor) setup
        // on Android BLE scheduling. Raising to 100 Hz on 9 sensors saturates the radio and
        // produces high jitter + big gaps (see diagnostics). 50 Hz is plenty for stroke
        // timing: Nyquist covers rowing harmonics and sub-sample interpolation keeps catch
        // precision in the 5–10 ms range. Bump to RRATE_100HZ for bench or 1–2 sensor tests.
        @Volatile var targetRateRegister: Int = RRATE_50HZ

        // Debug toggle: when true, the config chain on connect ALSO writes RSW=0x0001 so
        // the sensor emits 0x55 0x50 TIME packets alongside the default 0x55 0x61. Used to
        // measure whether enabling a parallel notification stream halves effective 0x61
        // throughput on a constrained BLE link. Read at config-write time only — toggling
        // mid-connection has no effect; user must reconnect for the change to land.
        @Volatile var enableTimePacket: Boolean = false

        fun expectedHzFor(rrate: Int): Int = when (rrate) {
            RRATE_10HZ  -> 10
            RRATE_20HZ  -> 20
            RRATE_50HZ  -> 50
            RRATE_100HZ -> 100
            RRATE_200HZ -> 200
            else -> 0
        }
    }

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(
        deviceAddress: String,
        onSample: (
            ax: Float,
            ay: Float,
            az: Float,
            wx: Float,
            wy: Float,
            wz: Float,
            roll: Float,
            pitch: Float,
            yaw: Float
        ) -> Unit,
        onStatus: (String) -> Unit,
        onDisconnected: (() -> Unit)? = null,
        onConnectionStateChange: ((status: Int, newState: Int) -> Unit)? = null
    ) {
        val macLog = bleMacForLog(deviceAddress)
        val scanPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasConnectPermission()) {
            val msg = "Missing BLUETOOTH_CONNECT permission for $macLog"
            if (DEBUG_BLE) Log.e(tag, "$msg sdk=${Build.VERSION.SDK_INT} scanPerm=$scanPermGranted")
            onStatus(msg)
            return
        }

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val adapterEnabled = adapter?.isEnabled == true
        val device = adapter?.getRemoteDevice(deviceAddress)

        if (device == null) {
            val msg = "Device not found for address $macLog"
            if (DEBUG_BLE) Log.e(tag, "$msg sdk=${Build.VERSION.SDK_INT} adapterEnabled=$adapterEnabled")
            onStatus(msg)
            return
        }

        sampleCallback = onSample
        statusCallback = onStatus
        disconnectCallback = onDisconnected
        // pendingWrites / writeInFlight / writeCharacteristic can be touched from the BLE
        // binder thread in parallel with a caller driving connect(). Serialize the reset
        // through the same monitor the binder callbacks use for counter access.
        synchronized(this) {
            writeCharacteristic = null
            pendingWrites.clear()
            writeInFlight = false
        }
        resetCountersForNewConnection()
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null

        if (DEBUG_BLE) {
            Log.d(
                tag,
                "Connect attempt mac=$macLog sdk=${Build.VERSION.SDK_INT} " +
                    "adapterEnabled=$adapterEnabled connectPerm=${hasConnectPermission()} scanPerm=$scanPermGranted " +
                    "targetRRATE=0x${"%02X".format(targetRateRegister)}"
            )
        }
        onStatus("Connecting to $deviceAddress ...")

        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(gatt, status, newState)
                onConnectionStateChange?.invoke(status, newState)
                if (DEBUG_BLE) {
                    Log.d(
                        tag,
                        "GATT state mac=$macLog status=$status(${gattStatusToReason(status)}) " +
                            "newState=$newState(${stateToName(newState)})"
                    )
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordGattStatus(status)
                    statusCallback?.invoke("GATT error on $macLog status=$status (${gattStatusToReason(status)})")
                    disconnectCallback?.invoke()
                    gatt.close()
                    this@BleDeviceClient.gatt = null
                    return
                }

                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        statusCallback?.invoke("Connected to $macLog, discovering services...")
                        // Ask for the shortest connection interval the phone will grant.
                        // This is the primary throughput lever — default BALANCED gives 30–50ms intervals.
                        try {
                            val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            if (DEBUG_BLE) Log.d(tag, "requestConnectionPriority(HIGH) mac=$macLog ok=$ok")
                        } catch (e: Exception) {
                            if (DEBUG_BLE) Log.e(tag, "requestConnectionPriority failed mac=$macLog: ${e.message}")
                        }
                        gatt.discoverServices()
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        statusCallback?.invoke("Disconnected from $macLog")
                        disconnectCallback?.invoke()
                        gatt.close()
                        this@BleDeviceClient.gatt = null
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordGattStatus(status)
                    statusCallback?.invoke("Service discovery failed on $macLog status=$status (${gattStatusToReason(status)})")
                    if (DEBUG_BLE) Log.e(tag, "Service discovery failed mac=$macLog status=$status(${gattStatusToReason(status)})")
                    return
                }

                val services = gatt.services
                if (services.isEmpty()) {
                    statusCallback?.invoke("No services found on $macLog")
                    if (DEBUG_BLE) Log.e(tag, "No services found mac=$macLog")
                    return
                }

                // Opportunistically request 2M PHY. nRF52832 supports it but WT9011DCL firmware
                // exposure is undocumented — treat failure as fine, packets still flow at 1M.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                    } catch (e: Exception) {
                        if (DEBUG_BLE) Log.d(tag, "setPreferredPhy not supported mac=$macLog: ${e.message}")
                    }
                }

                // Pin canonical WT9011DCL UUIDs. NOTIFY falls back to the first notifiable
                // characteristic so older / non-canonical WIT firmware still streams data.
                // WRITE does NOT fall back — config commands are WitMotion-specific and firing
                // them at a random writable characteristic on a non-WitMotion device could
                // corrupt DFU control, battery services, etc.
                val witService: BluetoothGattService? = gatt.getService(SERVICE_UUID)
                val notifyChar: BluetoothGattCharacteristic? = witService?.getCharacteristic(NOTIFY_UUID)
                    ?: firstNotifyCharacteristic(services)
                writeCharacteristic = witService?.getCharacteristic(WRITE_UUID)

                if (notifyChar == null) {
                    statusCallback?.invoke("No notifiable characteristics found on $macLog")
                    if (DEBUG_BLE) Log.e(tag, "No notifiable characteristics mac=$macLog")
                    return
                }

                if (DEBUG_BLE) {
                    Log.d(
                        tag,
                        "UUIDs mac=$macLog notify=${notifyChar.uuid} write=${writeCharacteristic?.uuid ?: "<none>"}"
                    )
                }

                statusCallback?.invoke("Subscribing on $macLog to ${notifyChar.uuid}")
                enableNotifications(gatt, notifyChar)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                // Reject callbacks from a torn-down gatt. CCCD UUID is a constant and
                // would otherwise pass the UUID check, briefly poisoning config state on
                // the current connection.
                if (gatt !== this@BleDeviceClient.gatt) return
                if (descriptor.uuid != CCCD_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordGattStatus(status)
                    markConfigFailed("CCCD write status=$status")
                    if (DEBUG_BLE) Log.e(tag, "CCCD write failed mac=$macLog status=$status")
                    return
                }
                if (DEBUG_BLE) Log.d(tag, "CCCD enabled mac=$macLog — queueing config writes")
                // Notifications are live. Push config: unlock → set ODR → set RSW → save.
                // Skip (and mark config un-applied) if we don't have the canonical write
                // characteristic — sensor continues streaming at whatever rate its flash holds.
                val wc = writeCharacteristic
                if (wc == null) {
                    markConfigFailed("no canonical write characteristic")
                    if (DEBUG_BLE) Log.w(tag, "No WIT write UUID on $macLog — skipping ODR config")
                    return
                }
                val rswValue = if (enableTimePacket) RSW_TIME_BIT else RSW_OFF
                // Always write RSW too: leaves the sensor in a known state regardless of
                // whatever bitmask happened to be saved in its flash from prior sessions.
                val frames = listOf(CMD_UNLOCK, cmdSetRate(targetRateRegister), cmdSetRsw(rswValue), CMD_SAVE)
                beginConfigSequence(frames.size)
                frames.forEach { pendingWrites.addLast(it) }
                processNextWrite(gatt)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                // Reject callbacks from a torn-down gatt so an old config-write success
                // can't prematurely decrement the new connection's configPendingCount.
                if (gatt !== this@BleDeviceClient.gatt) return
                if (characteristic.uuid != writeCharacteristic?.uuid) return
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordGattStatus(status)
                    markConfigFailed("config write status=$status")
                    // Abort the rest of the chain — writing `set-rate` after a failed unlock
                    // would just go to a locked sensor. Clear the queue so we don't keep trying.
                    pendingWrites.clear()
                    if (DEBUG_BLE) Log.e(tag, "Config write failed mac=$macLog status=$status — aborting chain")
                    return
                }
                onConfigWriteSucceeded()
                processNextWrite(gatt)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicChanged(gatt, characteristic)

                val bytes = characteristic.value ?: return

                // Expect WIT packet: 0x55 <flag> ...
                if (bytes.size < 4 || bytes[0] != 0x55.toByte()) {
                    bumpMalformed()
                    return
                }
                val flag = bytes[1].toUByte().toInt()
                if (flag == 0x50) {
                    // TIME packet (debug toggle). 10 bytes total. We don't decode the
                    // timestamp here — just count arrivals so the diag screen can show
                    // whether enabling it cuts effective 0x61 throughput on a busy link.
                    bumpTimeReceived()
                    return
                }
                if (flag != 0x61 || bytes.size < 20) {
                    bumpMalformed()
                    return
                }

                fun s16(lo: Byte, hi: Byte): Int {
                    return ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort().toInt()
                }

                val axRaw = s16(bytes[2], bytes[3])
                val ayRaw = s16(bytes[4], bytes[5])
                val azRaw = s16(bytes[6], bytes[7])
                val wxRaw = s16(bytes[8], bytes[9])
                val wyRaw = s16(bytes[10], bytes[11])
                val wzRaw = s16(bytes[12], bytes[13])
                val rollRaw  = s16(bytes[14], bytes[15])
                val pitchRaw = s16(bytes[16], bytes[17])
                val yawRaw   = s16(bytes[18], bytes[19])

                val ax = axRaw / 32768.0f * 16f
                val ay = ayRaw / 32768.0f * 16f
                val az = azRaw / 32768.0f * 16f
                val wx = wxRaw / 32768.0f * 2000f
                val wy = wyRaw / 32768.0f * 2000f
                val wz = wzRaw / 32768.0f * 2000f
                val roll  = rollRaw  / 32768.0f * 180f
                val pitch = pitchRaw / 32768.0f * 180f
                val yaw   = yawRaw   / 32768.0f * 180f

                sampleCallback?.invoke(ax, ay, az, wx, wy, wz, roll, pitch, yaw)
                bumpReceivedWithGap()
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (DEBUG_BLE) Log.d(tag, "MTU mac=$macLog mtu=$mtu status=$status")
            }

            override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                super.onPhyUpdate(gatt, txPhy, rxPhy, status)
                if (DEBUG_BLE) Log.d(tag, "PHY mac=$macLog tx=$txPhy rx=$rxPhy status=$status")
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)
                if (status == BluetoothGatt.GATT_SUCCESS) recordRssi(rssi)
            }

            // NOTE: BluetoothGattCallback.onConnectionUpdated (the LL connection-interval
            // callback) is a hidden/@SystemApi method — not overridable by a normal app
            // compiling against the public SDK, so it was removed (it never compiled).
            // The connectionIntervalMs diagnostic stays null and renders as "—" until a
            // public API exposes the negotiated interval. See lastConnectionIntervalUnits.
        })
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite(gatt: BluetoothGatt) {
        if (writeInFlight) return
        val ch = writeCharacteristic ?: return
        val next = pendingWrites.removeFirstOrNull() ?: return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = next
        writeInFlight = true
        val ok = gatt.writeCharacteristic(ch)
        if (!ok) {
            writeInFlight = false
            if (DEBUG_BLE) Log.e(tag, "writeCharacteristic returned false — dropping pending config")
            pendingWrites.clear()
        } else if (DEBUG_BLE) {
            Log.d(tag, "Config write enqueued len=${next.size} first=0x${"%02X".format(next[2])}")
        }
    }

    @Synchronized
    private fun bumpReceivedWithGap() {
        val now = SystemClock.elapsedRealtime()
        received++
        if (lastSampleElapsedMs != 0L) {
            val gap = now - lastSampleElapsedMs
            if (gap > maxGapMs) maxGapMs = gap
            val g = gap.toDouble()
            intervalN++
            intervalSum += g
            intervalSumSq += g * g
        }
        lastSampleElapsedMs = now
    }

    @Synchronized
    private fun bumpMalformed() {
        malformed++
    }

    @Synchronized
    private fun bumpTimeReceived() {
        timeReceived++
    }

    @Synchronized
    private fun recordGattStatus(status: Int) {
        lastGattStatus = status
    }

    @Synchronized
    private fun recordRssi(rssi: Int) {
        lastRssi = rssi
    }

    @Synchronized
    private fun beginConfigSequence(count: Int) {
        configPendingCount = count
        configFailed = false
        configApplied = false
    }

    @Synchronized
    private fun onConfigWriteSucceeded() {
        if (configPendingCount > 0) configPendingCount--
        if (configPendingCount == 0 && !configFailed) configApplied = true
    }

    @Synchronized
    private fun markConfigFailed(reason: String) {
        configFailed = true
        configApplied = false
        configPendingCount = 0
        if (DEBUG_BLE) Log.e(tag, "Config failed: $reason")
    }

    @Synchronized
    private fun resetCountersForNewConnection() {
        received = 0
        malformed = 0
        maxGapMs = 0L
        intervalN = 0
        intervalSum = 0.0
        intervalSumSq = 0.0
        timeReceived = 0
        // Reset across connections — otherwise the first gap after a reconnect spans the
        // entire disconnect duration and poisons maxGapMs for that window.
        lastSampleElapsedMs = 0L
        lastRssi = null
        lastGattStatus = null
        lastConnectionIntervalUnits = null
        configPendingCount = 0
        configFailed = false
        configApplied = false
    }

    /**
     * Snapshot per-window counters (received, malformed, max gap, jitter) and clear them.
     * Connection-scoped state (rssi, lastGattStatus, configApplied) is returned as-is
     * and persists across snapshots until the next connect().
     */
    @Synchronized
    fun snapshotAndReset(): BleDiagnosticSnapshot {
        val jitter = if (intervalN > 1) {
            val mean = intervalSum / intervalN
            val variance = (intervalSumSq / intervalN) - (mean * mean)
            if (variance > 0) sqrt(variance) else 0.0
        } else 0.0
        val snap = BleDiagnosticSnapshot(
            received = received,
            malformed = malformed,
            maxGapMs = maxGapMs,
            jitterMs = jitter,
            rssi = lastRssi,
            lastGattStatus = lastGattStatus,
            configApplied = configApplied,
            configFailed = configFailed,
            connectionIntervalMs = lastConnectionIntervalUnits?.let { it * 1.25 },
            timeReceived = timeReceived
        )
        received = 0
        malformed = 0
        maxGapMs = 0L
        intervalN = 0
        intervalSum = 0.0
        intervalSumSq = 0.0
        timeReceived = 0
        return snap
    }

    private fun firstNotifyCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        for (service in services) {
            for (ch in service.characteristics) {
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) return ch
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (DEBUG_BLE) {
            Log.d(
                tag,
                "setCharacteristicNotification mac=${bleMacForLog(gatt.device?.address)} ok=$ok char=${characteristic.uuid}"
            )
        }

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeOk = gatt.writeDescriptor(cccd)
            if (DEBUG_BLE) Log.d(tag, "writeDescriptor CCCD mac=${bleMacForLog(gatt.device?.address)} ok=$writeOk")
        } else {
            if (DEBUG_BLE) {
                Log.e(tag, "Missing CCCD descriptor mac=${bleMacForLog(gatt.device?.address)} char=${characteristic.uuid}")
            }
        }
    }

    /**
     * Re-request high connection priority. Android silently drifts back to BALANCED
     * after idle or on low battery — call this periodically during active recording.
     */
    @SuppressLint("MissingPermission")
    fun reassertConnectionPriority() {
        val g = gatt ?: return
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (_: Exception) {
        }
    }

    /**
     * Kick off an async RSSI read. The result lands in onReadRemoteRssi and is cached
     * in lastRssi for the next snapshotAndReset(). At 1 Hz this is a cheap GATT op.
     */
    @SuppressLint("MissingPermission")
    fun requestRssiRead() {
        try { gatt?.readRemoteRssi() } catch (_: Exception) {}
    }

    fun disconnect() {
        if (DEBUG_BLE) Log.d(tag, "disconnect requested mac=${bleMacForLog(gatt?.device?.address)}")
        gatt?.close()
        gatt = null
        synchronized(this) {
            pendingWrites.clear()
            writeInFlight = false
            writeCharacteristic = null
        }
    }

    private fun stateToName(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN"
        }
    }

    private fun gattStatusToReason(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            8 -> "GATT_CONN_TIMEOUT"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            34 -> "GATT_CONN_LMP_TIMEOUT"
            62 -> "GATT_CONN_FAIL_ESTABLISH"
            133 -> "GATT_ERROR_133"
            257 -> "GATT_CONN_CANCEL"
            else -> "UNKNOWN_GATT_STATUS"
        }
    }
}
