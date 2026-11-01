package com.strongcodr.syncrow.model

data class SensorDiagnostic(
    val timestampMs: Long,
    /** Monotonic elapsed time (SystemClock.elapsedRealtime) at the window tick —
     *  immune to wall-clock jumps. Defaulted so older stored diagnostics still load. */
    val elapsedMs: Long = 0L,
    val windowDurationMs: Long,
    val sensorMac: String,
    val sensorId: String?,
    val seat: String?,
    val intervalId: Long,
    /** Frames that actually arrived in this window. */
    val received: Int,
    /** Observed rate: received frames per second over the window. There is no expected
     *  rate — the unit sends at whatever it is set to (1–200 Hz). Defaulted so older
     *  stored diagnostics (which carried expected/dropPct instead) still load. */
    val hz: Double = 0.0,
    val maxGapMs: Long,
    val jitterMs: Double,
    val malformed: Int,
    val rssi: Int?,
    val connected: Boolean,
    val configApplied: Boolean,
    val configFailed: Boolean = false,
    val reconnectsThisWindow: Int,
    val lastGattStatus: Int?,
    /** Negotiated BLE connection interval in milliseconds. Null until first
     *  onConnectionUpdated callback fires (API 26+). */
    val connectionIntervalMs: Double? = null,
    /** Count of 0x55 0x50 TIME packets received this window. Zero unless the debug
     *  TIME-packet toggle is enabled. Used to A/B test whether enabling parallel TIME
     *  notifications cuts effective 0x61 throughput on a saturated link. */
    val timeReceived: Int = 0
)
