package com.strongcodr.syncrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table test for the RRATE → Hz translation. Guards against someone silently
 * mis-editing a constant: if the protocol spec says "0x0B = 200 Hz" and we claim
 * "0x0B = 100 Hz," every downstream drop% calculation is wrong.
 */
class BleDeviceClientExpectedHzTest {

    @Test
    fun `RRATE register values map to documented Hz`() {
        assertEquals(10,  BleDeviceClient.expectedHzFor(BleDeviceClient.RRATE_10HZ))
        assertEquals(20,  BleDeviceClient.expectedHzFor(BleDeviceClient.RRATE_20HZ))
        assertEquals(50,  BleDeviceClient.expectedHzFor(BleDeviceClient.RRATE_50HZ))
        assertEquals(100, BleDeviceClient.expectedHzFor(BleDeviceClient.RRATE_100HZ))
        assertEquals(200, BleDeviceClient.expectedHzFor(BleDeviceClient.RRATE_200HZ))
    }

    @Test
    fun `unknown rrate returns zero — signals dropPct to stay at 0`() {
        // buildDiagnosticRow branches on `expectedThisWindow > 0` to avoid division;
        // expectedHzFor returning 0 for any unknown value keeps the downstream
        // calculation safe instead of blowing up.
        assertEquals(0, BleDeviceClient.expectedHzFor(0x00))
        assertEquals(0, BleDeviceClient.expectedHzFor(0x0A)) // protocol gap — no RRATE at 0x0A
        assertEquals(0, BleDeviceClient.expectedHzFor(0xFF))
    }

    @Test
    fun `protocol register constants match WitMotion WT9011DCL spec`() {
        // Sanity: if someone changes a constant, they have to intentionally break this test.
        assertEquals(0x06, BleDeviceClient.RRATE_10HZ)
        assertEquals(0x07, BleDeviceClient.RRATE_20HZ)
        assertEquals(0x08, BleDeviceClient.RRATE_50HZ)
        assertEquals(0x09, BleDeviceClient.RRATE_100HZ)
        assertEquals(0x0B, BleDeviceClient.RRATE_200HZ)
    }
}
