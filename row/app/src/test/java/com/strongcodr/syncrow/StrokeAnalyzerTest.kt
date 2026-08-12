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
 * StrokeAnalyzer: reference selection (stroke = highest seat), per-seat quality
 * gating (shared spec, RESEARCH.md §8.5 — one bad seat never blanks the crew), and
 * double-count rejection (Schmitt trigger + refractory).
 */
class StrokeAnalyzerTest {

    private val hz = 25.0                 // fresh-sample rate (effective BLE rate)
    private val dtMs = (1000.0 / hz).toLong()
    private val strokeHz = 0.5            // 30 spm
    private val amp = 40f                 // pitch swing, degrees

    /** Pitch as a clean stroke oscillation, phase-shifted by phaseMs. */
    private fun pitchAt(tMs: Long, phaseMs: Long): Float =
        amp * sin(2 * PI * strokeHz * (tMs - phaseMs) / 1000.0).toFloat()

    /** Gyro magnitude that's high on the drive (after the catch) so the analyzer
     *  can resolve which crossing is the catch. */
    private fun driveGyro(tMs: Long): Float =
        if (cos(2 * PI * strokeHz * tMs / 1000.0) > 0) 300f else 40f

    // ─── reference = stroke = highest seat number ───────────────────────────

    @Test
    fun `reference is the highest seat number (stroke), reads zero`() {
        val analyzer = StrokeAnalyzer()
        analyzer.addSensor("bow", 1)      // seat 1 = bow (added first, on purpose)
        analyzer.addSensor("stroke", 2)   // seat 2 = stroke = should be the reference
        var t = 1_000_000L
        repeat(2000) {
            val wz = driveGyro(t)
            analyzer.onSample("stroke", t, true, pitchAt(t, 0), 0f, 0f, 0f, 0f, wz)
            analyzer.onSample("bow", t, true, pitchAt(t, 80), 0f, 0f, 0f, 0f, wz) // 80 ms late
            t += dtMs
        }
        // stroke (seat 2) is the reference → 0; bow (seat 1) carries the offset.
        assertEquals("stroke seat must be the reference (0)", 0L, analyzer.getLateness("stroke"))
        val bowLate = analyzer.getLateness("bow")
        assertTrue("bow must carry a non-zero lateness vs stroke", bowLate != null && bowLate != 0L)
    }

    // ─── one seat spaces out; the rest of the boat survives ─────────────────

    @Test
    fun `one seat spacing out degrades only that seat, boat stays live`() {
        val analyzer = StrokeAnalyzer()
        analyzer.addSensor("stroke", 2)   // reference (highest seat)
        analyzer.addSensor("bow", 1)      // the follower that spaces out

        var t = 1_000_000L
        val freezeStart = t + 48_000L      // ~48 s to calibrate + pair
        val freezeEnd = freezeStart + 6_000L   // bow frozen 6 s (>> a stroke)
        val end = freezeEnd + 12_000L

        var bowDegradedSeen = false
        var bowOkSeenAfterThaw = false

        while (t < end) {
            val wz = driveGyro(t)
            analyzer.onSample("stroke", t, true, pitchAt(t, 0), 0f, 0f, 0f, 0f, wz)
            val bowFresh = t < freezeStart || t >= freezeEnd
            analyzer.onSample("bow", t, bowFresh, pitchAt(t, 80), 0f, 0f, 0f, 0f, wz)

            val bowStatus = analyzer.getStatus("bow")
            val strokeStatus = analyzer.getStatus("stroke")

            if (t in (freezeStart + 2_000L)..(freezeEnd - dtMs)) {
                assertEquals("bow should be degraded while frozen",
                    SensorSyncStatus.DEGRADED_SIGNAL, bowStatus)
                assertNotEquals("stroke (reference) must not be dragged down by bow",
                    SensorSyncStatus.DEGRADED_SIGNAL, strokeStatus)
                bowDegradedSeen = true
            }
            if (t > freezeEnd + 6_000L && bowStatus == SensorSyncStatus.OK) {
                bowOkSeenAfterThaw = true
            }
            t += dtMs
        }
        assertTrue("bow degradation must be detected during the freeze", bowDegradedSeen)
        assertTrue("bow must recover to OK after fresh data resumes", bowOkSeenAfterThaw)
    }

    // ─── held samples are skipped; a stalled seat goes degraded ─────────────

    @Test
    fun `a calibrated seat that then holds goes degraded`() {
        val analyzer = StrokeAnalyzer()
        analyzer.addSensor("stroke", 2)   // reference
        analyzer.addSensor("bow", 1)
        var t = 1_000_000L
        // Phase 1: bow gets fresh data and calibrates.
        repeat(1500) {
            val wz = driveGyro(t)
            analyzer.onSample("stroke", t, true, pitchAt(t, 0), 0f, 0f, 0f, 0f, wz)
            analyzer.onSample("bow", t, true, pitchAt(t, 60), 0f, 0f, 0f, 0f, wz)
            t += dtMs
        }
        // Phase 2: bow's BLE dies — every tick held with junk that must be skipped.
        repeat(1500) {
            analyzer.onSample("stroke", t, true, pitchAt(t, 0), 0f, 0f, 0f, 0f, driveGyro(t))
            analyzer.onSample("bow", t, false, 999f, 0f, 0f, 0f, 0f, 0f) // held junk, skipped
            t += dtMs
        }
        assertEquals(SensorSyncStatus.DEGRADED_SIGNAL, analyzer.getStatus("bow"))
    }

    // ─── Schmitt trigger + refractory reject double-counts ──────────────────

    @Test
    fun `noisy stroke yields about one catch per cycle, not double`() {
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
            val wz = if (cos(th) > 0) 300f else 40f
            val r = a.onSample("stroke", t, true, pitch, 0f, 0f, 0f, 0f, wz)
            if (t > start + 15_000L && r != null) catches++  // count after warmup/phase lock
            t += dtMs
        }
        // window = 25 s at 0.5 Hz ≈ 12–13 real strokes. A double-count would be ~25.
        assertTrue("detected too few catches ($catches) — hysteresis too wide?", catches >= 7)
        assertTrue("double-counting: $catches catches for ~12 strokes", catches <= 18)
    }
}
