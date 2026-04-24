package com.strongcodr.syncrow.model

data class SensorDiagnostic(
    val timestampMs: Long,
    val elapsedMs: Long,
    val windowDurationMs: Long,
    val sensorMac: String,
    val sensorId: String?,
    val seat: String?,
    val intervalId: Long,
    val expected: Int,
    val received: Int,
    val dropPct: Double,
    val maxGapMs: Long,
    val jitterMs: Double,
    val malformed: Int,
    val rssi: Int?,
    val connected: Boolean,
    val configApplied: Boolean,
    val configFailed: Boolean = false,
    val reconnectsThisWindow: Int,
    val lastGattStatus: Int?
)
