package com.strongcodr.syncrow

/**
 * Output of [buildSensorLabels]. All three lists are the same length as the input
 * sensor list and indexed positionally. `roles` is carried separately so the service
 * doesn't have to infer it from label equality downstream.
 */
internal data class SensorLabels(
    val ids: List<String>,
    val seats: List<String>,
    val displayNames: List<String>,
    val roles: List<String> // SensorRole.name() per entry
)

/** Visible to IntervalRecordingService (where it's re-exported as COX_LABEL) and to tests. */
internal const val COX_LABEL_VALUE = "Cox"

/**
 * Pure sensor-labeling logic. Rowers get "Seat N" (sweep) or "Seat N Port/Starboard"
 * (sculling); cox gets "Cox" regardless of mode. Seat numbers are derived from rower
 * position only — the cox's presence in the list does not shift rower seat numbers.
 *
 * Extracted from IntervalRecordingService so unit tests can cover the numbering
 * without instantiating an Android Service.
 */
internal fun buildSensorLabels(sensors: List<Sensor>, mode: RowingMode): SensorLabels {
    if (sensors.isEmpty()) return SensorLabels(emptyList(), emptyList(), emptyList(), emptyList())

    val rowerCount = sensors.count { it.role == SensorRole.SEAT }
    val scullingSeatCount = (rowerCount + 1) / 2

    val ids = ArrayList<String>(sensors.size)
    val seats = ArrayList<String>(sensors.size)
    val displayNames = ArrayList<String>(sensors.size)
    val roles = ArrayList<String>(sensors.size)

    var rowerIdx = 0
    sensors.forEach { sensor ->
        roles.add(sensor.role.name)
        if (sensor.role == SensorRole.COX) {
            val custom = sensor.name?.trim()?.takeIf { it.isNotEmpty() }
            ids.add(COX_LABEL_VALUE)
            seats.add(COX_LABEL_VALUE)
            displayNames.add(custom ?: COX_LABEL_VALUE)
        } else {
            if (mode == RowingMode.SWEEP) {
                val seatNumber = rowerCount - rowerIdx
                ids.add("Seat $seatNumber")
                seats.add("Seat $seatNumber")
                displayNames.add(
                    sensor.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Seat $seatNumber"
                )
            } else {
                val seatNumber = scullingSeatCount - (rowerIdx / 2)
                val side = if (rowerIdx % 2 == 0) "Port" else "Starboard"
                ids.add("Seat $seatNumber $side")
                seats.add("Seat $seatNumber")
                displayNames.add(
                    sensor.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Seat $seatNumber $side"
                )
            }
            rowerIdx++
        }
    }

    return SensorLabels(ids, seats, displayNames, roles)
}
