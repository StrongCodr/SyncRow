package com.strongcodr.syncrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the sanitization logic invoked at ViewModel load. Two invariants:
 * - WitMotion broadcast names ("WT...") are cleared so they don't stick as labels.
 * - At most one sensor retains the COX role; extras are demoted to SEAT.
 */
class SensorsViewModelSanitizeTest {

    private fun sensor(
        id: Long,
        mac: String = "AA:$id",
        name: String? = null,
        role: SensorRole = SensorRole.SEAT
    ) = Sensor(id = id, mac = mac, name = name, role = role)

    @Test
    fun `empty list passes through unchanged`() {
        val result = SensorsViewModel.sanitizeLoaded(emptyList())
        assertEquals(emptyList<Sensor>(), result)
    }

    @Test
    fun `WT-prefix names cleared`() {
        val input = listOf(
            sensor(1, name = "WT901BLE6859"),
            sensor(2, name = "Alice"),
            sensor(3, name = null),
            sensor(4, name = " WT_lowercase"),
        )
        val result = SensorsViewModel.sanitizeLoaded(input)
        assertNull(result[0].name)
        assertEquals("Alice", result[1].name)
        assertNull(result[2].name)
        // Leading whitespace trimmed before the prefix check — case-insensitive too.
        assertNull(result[3].name)
    }

    @Test
    fun `single cox retained`() {
        val input = listOf(sensor(1, role = SensorRole.COX), sensor(2), sensor(3))
        val result = SensorsViewModel.sanitizeLoaded(input)
        assertEquals(SensorRole.COX, result[0].role)
        assertEquals(SensorRole.SEAT, result[1].role)
        assertEquals(SensorRole.SEAT, result[2].role)
    }

    @Test
    fun `multiple cox invariant enforced — first wins, rest demoted`() {
        // Simulates a corrupted sensors.json or a race. Without this sanitization,
        // remapSensorsForModeSwitch would silently drop the extra cox entries.
        val input = listOf(
            sensor(1, role = SensorRole.COX),
            sensor(2),
            sensor(3, role = SensorRole.COX),
            sensor(4, role = SensorRole.COX),
        )
        val result = SensorsViewModel.sanitizeLoaded(input)
        assertEquals(SensorRole.COX, result[0].role)
        assertEquals(SensorRole.SEAT, result[1].role)
        assertEquals(SensorRole.SEAT, result[2].role)
        assertEquals(SensorRole.SEAT, result[3].role)
        assertEquals(1, result.count { it.role == SensorRole.COX })
    }

    @Test
    fun `sanitize preserves order and other fields`() {
        val input = listOf(
            sensor(1, mac = "AA:11", name = "Alice", role = SensorRole.COX),
            sensor(2, mac = "AA:22", name = "Bob", role = SensorRole.COX),
        )
        val result = SensorsViewModel.sanitizeLoaded(input)
        assertEquals(listOf(1L, 2L), result.map { it.id })
        assertEquals(listOf("AA:11", "AA:22"), result.map { it.mac })
        assertEquals(listOf("Alice", "Bob"), result.map { it.name })
        assertEquals(listOf(SensorRole.COX, SensorRole.SEAT), result.map { it.role })
    }

    @Test
    fun `list with no cox stays all SEAT`() {
        val input = listOf(sensor(1), sensor(2), sensor(3))
        val result = SensorsViewModel.sanitizeLoaded(input)
        assertTrue(result.all { it.role == SensorRole.SEAT })
    }
}
