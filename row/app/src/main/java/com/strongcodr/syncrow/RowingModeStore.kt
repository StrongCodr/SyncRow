package com.strongcodr.syncrow

import android.content.Context

enum class RowingMode {
    SWEEP,
    SCULLING
}

/**
 * Rowing mode semantics in SyncRow.
 *
 * There are two modes:
 * - UI mode: how sensors are presented and grouped (Sweep vs Sculling).
 * - Recording effective mode: how sensor identity is represented for interval recording and uploads.
 *
 * Compatibility contract (do not break):
 * - Upload identity MUST remain legacy "Seat N" for sensor_id in all modes.
 * - Sculling "Port/Starboard" is a UI and local mapping detail only.
 *
 * Why this exists:
 * Downstream ingestion and analytics assume legacy sensor_id format. Changing sensor_id to include
 * Port/Starboard can silently break joins and queries.
 *
 * Implementation:
 * - UI reads UI mode.
 * - Recording path uses effective mode (currently forced to Sweep) to preserve legacy identity.
 *
 * If you change this file, re-run the manual checklist:
 * pairing log, pre-start log, and verify recorded payload sensor_id values remain legacy.
 */
object RowingModeStore {
    private const val PREFS = "syncrow_prefs"
    private const val KEY_ROWING_MODE = "rowing_mode"

    fun getUiMode(context: Context): RowingMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROWING_MODE, RowingMode.SWEEP.name)
        return runCatching { RowingMode.valueOf(raw ?: RowingMode.SWEEP.name) }
            .getOrDefault(RowingMode.SWEEP)
    }

    fun getRecordingMode(@Suppress("UNUSED_PARAMETER") context: Context): RowingMode {
        // Recording path is pinned to SWEEP to preserve legacy upload identity (sensor_id = "Seat N").
        return RowingMode.SWEEP
    }

    fun set(context: Context, mode: RowingMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROWING_MODE, mode.name)
            .apply()
    }
}

object CrewLayout {

    data class ScullingSeat(
        val seatNumber: Int,
        val port: Sensor?,
        val starboard: Sensor?
    ) {
        fun isComplete(): Boolean = port != null && starboard != null
    }

    fun toScullingSeats(sensors: List<Sensor>): List<ScullingSeat> {
        if (sensors.isEmpty()) return emptyList()

        val seatCount = (sensors.size + 1) / 2
        val seats = mutableListOf<ScullingSeat>()

        var idx = 0
        var seatNumber = seatCount
        while (idx < sensors.size) {
            val port = sensors.getOrNull(idx)
            val starboard = sensors.getOrNull(idx + 1)
            seats += ScullingSeat(
                seatNumber = seatNumber,
                port = port,
                starboard = starboard
            )
            idx += 2
            seatNumber -= 1
        }

        return seats
    }

    fun remapSensorsForModeSwitch(
        sensors: List<Sensor>,
        from: RowingMode,
        to: RowingMode
    ): List<Sensor> {
        if (from == to || sensors.isEmpty()) return sensors

        // Defensive normalization: preserve first occurrence order and ignore accidental duplicates.
        val normalized = sensors.distinctBy { it.id }

        return when {
            from == RowingMode.SCULLING && to == RowingMode.SWEEP -> {
                // Explicitly flatten by seat (highest to lowest), Port then Starboard.
                val flattened = mutableListOf<Sensor>()
                toScullingSeats(normalized).forEach { seat ->
                    if (seat.port != null) flattened += seat.port
                    if (seat.starboard != null) flattened += seat.starboard
                }
                flattened
            }
            from == RowingMode.SWEEP && to == RowingMode.SCULLING -> {
                // Explicitly pair consecutive sweep seats: high->Port, low->Starboard.
                // Odd count: final sensor becomes Port of the lowest sculling seat.
                val paired = mutableListOf<Sensor>()
                var idx = 0
                while (idx < normalized.size) {
                    val high = normalized[idx]
                    paired += high
                    val low = normalized.getOrNull(idx + 1)
                    if (low != null) paired += low
                    idx += 2
                }
                paired
            }
            else -> normalized
        }
    }
}
