package com.strongcodr.syncrow

import com.strongcodr.syncrow.model.SensorSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-seat quality gating (shared spec, RESEARCH.md §8.5): a seat that spaces out
 * must be flagged on its own and its stale lateness suppressed, while every other
 * seat keeps updating. One bad seat never blanks the crew.
 */
class StrokeAnalyzerTest {

    private val hz = 25.0                 // fresh-sample rate (effective BLE rate)
    private val dtMs = (1000.0 / hz).toLong()
    private val strokeHz = 0.5            // 30 spm
    private val amp = 40f                 // pitch swing, degrees

    /** Drive one sensor's pitch as a clean stroke oscillation. */
    private fun pitchAt(tMs: Long, phaseMs: Long): Float =
        amp * sin(2 * PI * strokeHz * (tMs - phaseMs) / 1000.0).toFloat()

    @Test
    fun `one seat spacing out degrades only that seat, boat stays live`() {
        val analyzer = StrokeAnalyzer()
        // internal seat 1 = stroke reference (lowest index); seat 2 = other rower
        analyzer.addSensor("stroke", 1)
        analyzer.addSensor("bow", 2)

        var t = 1_000_000L
        val endWarmup = t + 40_000L        // ~40 s to calibrate + build periods
        val freezeStart = endWarmup + 8_000L
        val freezeEnd = freezeStart + 6_000L   // bow frozen 6 s (>> a stroke)
        val end = freezeEnd + 8_000L

        var bowDegradedSeen = false
        var bowOkSeenAfterThaw = false

        while (t < end) {
            val strokePitch = pitchAt(t, 0)
            val bowPitch = pitchAt(t, 80)  // bow catches 80 ms late

            // stroke seat: always fresh
            analyzer.onSample("stroke", t, true, strokePitch, 0f, 0f, 0f, 0f, 0f)

            // bow seat: fresh except during the freeze window (held/zero-order-hold)
            val bowFresh = t < freezeStart || t >= freezeEnd
            analyzer.onSample("bow", t, bowFresh, bowPitch, 0f, 0f, 0f, 0f, 0f)

            val bowStatus = analyzer.getStatus("bow")
            val strokeStatus = analyzer.getStatus("stroke")

            if (t in (freezeStart + 2_000L)..(freezeEnd - dtMs)) {
                // deep in the freeze: bow is degraded, but the stroke seat never is
                assertEquals("bow should be degraded while frozen",
                    SensorSyncStatus.DEGRADED_SIGNAL, bowStatus)
                assertNotEquals("stroke seat must not be dragged down by bow",
                    SensorSyncStatus.DEGRADED_SIGNAL, strokeStatus)
                bowDegradedSeen = true
            }
            if (t > freezeEnd + 4_000L && bowStatus == SensorSyncStatus.OK) {
                bowOkSeenAfterThaw = true
            }
            t += dtMs
        }

        assertTrue("bow degradation must be detected during the freeze", bowDegradedSeen)
        assertTrue("bow must recover to OK after fresh data resumes", bowOkSeenAfterThaw)
    }

    @Test
    fun `noisy stroke yields about one catch per cycle, not double`() {
        // A single sensor rowing at 30 spm with noise on the pitch signal. The
        // Schmitt trigger + refractory must give ~one catch per stroke; a bare
        // median-crossing would roughly double the count on the noise.
        val a = StrokeAnalyzer()
        a.addSensor("stroke", 1)
        val rng = Random(42)
        var t = 1_000_000L
        val start = t
        val dur = 40_000L
        var catches = 0
        while (t < start + dur) {
            val th = 2 * PI * strokeHz * (t / 1000.0)
            val pitch = (amp * sin(th) + rng.nextGaussian() * 6.0).toFloat() // ~15% noise
            val wz = if (cos(th) > 0) 300f else 40f  // higher gyro on the drive → phase lock
            val r = a.onSample("stroke", t, true, pitch, 0f, 0f, 0f, 0f, wz)
            if (t > start + 15_000L && r != null) catches++  // count after warmup/phase lock
            t += dtMs
        }
        // window = 25 s at 0.5 Hz ≈ 12–13 real strokes. A double-count would be ~25.
        assertTrue("detected too few catches ($catches) — hysteresis too wide?", catches >= 7)
        assertTrue("double-counting: $catches catches for ~12 strokes", catches <= 18)
    }

    @Test
    fun `held samples are never fed to detection`() {
        // A seat fed ONLY held samples must never produce a catch or a lateness.
        val analyzer = StrokeAnalyzer()
        analyzer.addSensor("stroke", 1)
        analyzer.addSensor("bow", 2)
        var t = 1_000_000L
        repeat(2000) {
            analyzer.onSample("stroke", t, true, pitchAt(t, 0), 0f, 0f, 0f, 0f, 0f)
            analyzer.onSample("bow", t, false, 12.34f, 0f, 0f, 0f, 0f, 0f) // held forever
            t += dtMs
        }
        assertEquals(SensorSyncStatus.DEGRADED_SIGNAL, analyzer.getStatus("bow"))
        // bow never paired, so it has no (stale) lateness to display
        assertEquals(null, analyzer.getLateness("bow"))
    }
}
