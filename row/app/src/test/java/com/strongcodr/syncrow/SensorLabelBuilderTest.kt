package com.strongcodr.syncrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [buildSensorLabels]. Covers rower numbering across sweep/sculling,
 * cox labeling, cox presence at arbitrary list positions, and custom names.
 */
class SensorLabelBuilderTest {

    private fun seat(id: Long, mac: String = "AA:$id", name: String? = null) =
        Sensor(id = id, mac = mac, name = name, role = SensorRole.SEAT)

    private fun cox(id: Long, mac: String = "CC:$id", name: String? = null) =
        Sensor(id = id, mac = mac, name = name, role = SensorRole.COX)

    @Test
    fun `empty list produces empty labels`() {
        val result = buildSensorLabels(emptyList(), RowingMode.SWEEP)
        assertEquals(emptyList<String>(), result.ids)
        assertEquals(emptyList<String>(), result.seats)
        assertEquals(emptyList<String>(), result.displayNames)
        assertEquals(emptyList<String>(), result.roles)
    }

    @Test
    fun `sweep rowers get seat N numbered from list end`() {
        // Convention: last rower in list = seat 1 (stroke reference for StrokeAnalyzer).
        val sensors = listOf(seat(1), seat(2), seat(3))
        val result = buildSensorLabels(sensors, RowingMode.SWEEP)
        assertEquals(listOf("Seat 3", "Seat 2", "Seat 1"), result.ids)
        assertEquals(listOf("Seat 3", "Seat 2", "Seat 1"), result.seats)
        assertEquals(listOf("SEAT", "SEAT", "SEAT"), result.roles)
    }

    @Test
    fun `sweep custom names used for displayName only`() {
        val sensors = listOf(seat(1, name = "Alice"), seat(2), seat(3, name = "Bow"))
        val result = buildSensorLabels(sensors, RowingMode.SWEEP)
        assertEquals(listOf("Seat 3", "Seat 2", "Seat 1"), result.ids)
        assertEquals(listOf("Alice", "Seat 2", "Bow"), result.displayNames)
    }

    @Test
    fun `cox alone without rowers still labels cox correctly`() {
        // Upstream guards reject cox-only at startInterval, but the labeler itself
        // should still produce a well-formed result.
        val result = buildSensorLabels(listOf(cox(1)), RowingMode.SWEEP)
        assertEquals(listOf("Cox"), result.ids)
        assertEquals(listOf("Cox"), result.seats)
        assertEquals(listOf("Cox"), result.displayNames)
        assertEquals(listOf("COX"), result.roles)
    }

    @Test
    fun `cox at index 0 does not shift rower seat numbers`() {
        val sensors = listOf(cox(99), seat(1), seat(2), seat(3))
        val result = buildSensorLabels(sensors, RowingMode.SWEEP)
        assertEquals(listOf("Cox", "Seat 3", "Seat 2", "Seat 1"), result.ids)
        assertEquals(listOf("COX", "SEAT", "SEAT", "SEAT"), result.roles)
    }

    @Test
    fun `cox at the end does not shift rower seat numbers`() {
        // Defensive: user drags cox below all rowers. Numbering must still be correct.
        val sensors = listOf(seat(1), seat(2), seat(3), cox(99))
        val result = buildSensorLabels(sensors, RowingMode.SWEEP)
        assertEquals(listOf("Seat 3", "Seat 2", "Seat 1", "Cox"), result.ids)
    }

    @Test
    fun `cox in middle does not shift rower seat numbers`() {
        val sensors = listOf(seat(1), cox(99), seat(2), seat(3))
        val result = buildSensorLabels(sensors, RowingMode.SWEEP)
        assertEquals(listOf("Seat 3", "Cox", "Seat 2", "Seat 1"), result.ids)
    }

    @Test
    fun `cox uses custom name as displayName when present`() {
        val sensors = listOf(cox(1, name = "Charlie"), seat(2), seat(3))
        val result = buildSensorLabels(sensors, RowingMode.SWEEP)
        assertEquals("Charlie", result.displayNames[0])
        // ID/seat tag stays "Cox" regardless of display name — the InfluxDB identity
        // is distinct from the UI display. Don't regress this.
        assertEquals("Cox", result.ids[0])
        assertEquals("Cox", result.seats[0])
    }

    @Test
    fun `sculling pairs rowers as port then starboard`() {
        // 4 rowers → 2 sculling seats. Pattern: idx0=seat2 Port, idx1=seat2 Starboard,
        // idx2=seat1 Port, idx3=seat1 Starboard.
        val sensors = listOf(seat(1), seat(2), seat(3), seat(4))
        val result = buildSensorLabels(sensors, RowingMode.SCULLING)
        assertEquals(
            listOf("Seat 2 Port", "Seat 2 Starboard", "Seat 1 Port", "Seat 1 Starboard"),
            result.ids
        )
        // Seat tag is the numeric-only form — shared between port and starboard.
        assertEquals(listOf("Seat 2", "Seat 2", "Seat 1", "Seat 1"), result.seats)
    }

    @Test
    fun `sculling odd rower count leaves final sensor as port of lowest seat`() {
        val sensors = listOf(seat(1), seat(2), seat(3))
        val result = buildSensorLabels(sensors, RowingMode.SCULLING)
        assertEquals(listOf("Seat 2 Port", "Seat 2 Starboard", "Seat 1 Port"), result.ids)
    }

    @Test
    fun `sculling with cox interleaved`() {
        val sensors = listOf(cox(99), seat(1), seat(2), seat(3), seat(4))
        val result = buildSensorLabels(sensors, RowingMode.SCULLING)
        assertEquals(
            listOf("Cox", "Seat 2 Port", "Seat 2 Starboard", "Seat 1 Port", "Seat 1 Starboard"),
            result.ids
        )
    }
}
