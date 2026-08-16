package com.strongcodr.syncrow

import com.strongcodr.syncrow.model.SensorSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ground truth for the UNIFIED synthetic-axis engine — mirrors the portal's
 * test_pipeline_recovers_injected_offsets: inject known per-seat offsets through
 * ARBITRARY sensor mountings and assert the phone recovers them (right sign +
 * magnitude). Plus the exact real-world failing case (two sensors, same motion in
 * one hand → ~0 ms), and unit checks on the math (Jacobi eigen, gaussianLag sign).
 */
class StrokeAnalyzerTest {

    private val fs = 50.0                 // fresh-sample rate fed to the analyzer
    private val dtMs = (1000.0 / fs).toLong()
    private val strokeHz = 0.45           // 27 spm
    private val amp = 100.0               // gyro sweep amplitude, deg/s

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    /**
     * Drive the whole crew and return the analyzer after `durMs`. Each sensor sees the
     * stroke rotation about its OWN arbitrary axis, time-shifted by its injected offset,
     * plus its own gravity — exactly the portal's synthetic crew. Samples are fed in
     * global time order (interleaved), at `fs`, all fresh.
     */
    private fun runCrew(
        offsetsS: Map<String, Double>, axes: Map<String, DoubleArray>,
        grav: Map<String, DoubleArray>, seat: Map<String, Int>,
        durMs: Long, driveBias: Boolean = true, noise: Double = 0.6, seed: Long = 1,
    ): StrokeAnalyzer {
        val a = StrokeAnalyzer()
        for ((name, idx) in seat) a.addSensor(name, idx)
        val rng = Random(seed)
        var t = 1_000_000L
        val end = t + durMs
        while (t < end) {
            for ((name, off) in offsetsS) {
                val th = 2 * PI * strokeHz * ((t - off * 1000.0) / 1000.0)
                // sweep angular velocity; drive half (sin>0) boosted so catch phase locks,
                // like real rowing (drive faster than recovery).
                var s = amp * sin(th)
                if (driveBias && s > 0) s *= 1.8
                val ax = unit(axes[name]!!)
                val gx = s * ax[0] + rng.nextGaussian() * noise
                val gy = s * ax[1] + rng.nextGaussian() * noise
                val gz = s * ax[2] + rng.nextGaussian() * noise
                val g = unit(grav[name]!!)
                a.onSample(
                    name, t, true,
                    (g[0] + rng.nextGaussian() * 0.01).toFloat(),
                    (g[1] + rng.nextGaussian() * 0.01).toFloat(),
                    (g[2] + rng.nextGaussian() * 0.01).toFloat(),
                    gx.toFloat(), gy.toFloat(), gz.toFloat(),
                )
            }
            t += dtMs
        }
        return a
    }

    // ─── the exact failing case: two sensors, same motion in one hand → ~0 ──────

    @Test
    fun `two sensors same motion report near-zero latency`() {
        val a = runCrew(
            offsetsS = mapOf("s1" to 0.0, "s2" to 0.0),
            axes = mapOf("s1" to doubleArrayOf(0.3, 0.8, 0.5), "s2" to doubleArrayOf(-0.6, 0.2, 0.75)),
            grav = mapOf("s1" to doubleArrayOf(0.0, 0.1, 1.0), "s2" to doubleArrayOf(0.1, 0.0, 1.0)),
            seat = mapOf("s1" to 1, "s2" to 2),   // s2 = stroke (reference)
            durMs = 40_000L,
        )
        assertEquals("stroke seat is the reference (0)", 0L, a.getLateness("s2"))
        val lat = a.getLateness("s1")
        assertTrue("expected a latency reading for s1", lat != null)
        assertTrue("two-in-one-hand must be ~0 ms, got $lat", abs(lat!!) <= 20)
    }

    // ─── GROUND TRUTH: recover injected offsets through arbitrary mountings ──────

    @Test
    fun `recovers injected offsets with correct sign through arbitrary axes`() {
        val injected = mapOf(
            "Seat 4" to 0.000,   // stroke (highest seat) = reference
            "Seat 3" to 0.000,
            "Seat 2" to 0.050,   // 50 ms LATE (behind)
            "Seat 1" to -0.030,  // 30 ms EARLY (ahead)
        )
        val axes = mapOf(
            "Seat 4" to doubleArrayOf(0.2, 0.9, -0.3), "Seat 3" to doubleArrayOf(-0.7, 0.1, 0.6),
            "Seat 2" to doubleArrayOf(0.4, -0.5, 0.8), "Seat 1" to doubleArrayOf(-0.3, -0.8, -0.4),
        )
        val grav = mapOf(
            "Seat 4" to doubleArrayOf(0.0, 0.1, 1.0), "Seat 3" to doubleArrayOf(0.1, 0.0, 1.0),
            "Seat 2" to doubleArrayOf(-0.1, 0.05, 1.0), "Seat 1" to doubleArrayOf(0.05, -0.1, 1.0),
        )
        val seat = mapOf("Seat 4" to 4, "Seat 3" to 3, "Seat 2" to 2, "Seat 1" to 1)
        val a = runCrew(injected, axes, grav, seat, durMs = 45_000L)

        assertEquals(0L, a.getLateness("Seat 4"))
        for ((name, offS) in injected) {
            if (name == "Seat 4") continue
            val got = a.getLateness(name)
            assertTrue("$name: no reading", got != null)
            val want = offS * 1000.0
            assertTrue("$name: got ${got}ms, want ${want}ms", abs(got!! - want) < 20.0)
            if (abs(want) > 20) assertTrue("$name wrong sign: $got vs $want",
                (got > 0) == (want > 0))
        }
    }

    // ─── math unit checks ───────────────────────────────────────────────────────

    @Test
    fun `gaussianLag sign - later signal gives positive lag`() {
        val n = 400
        val ref = DoubleArray(n) { sin(2 * PI * it / 40.0) }
        val later = DoubleArray(n) { sin(2 * PI * (it - 3) / 40.0) } // delayed 3 samples
        val est = StrokeAnalyzer.gaussianLag(ref, later, 50.0, 0.5)!!
        assertTrue("later signal must give +lag, got ${est.lagS}", est.lagS > 0)
        assertEquals(3.0 / 50.0, est.lagS, 0.008)
        assertTrue("clean match => high rho", est.rho > 0.95)
    }

    @Test
    fun `jacobi recovers a known dominant axis`() {
        // covariance dominated by direction d
        val d = unit(doubleArrayOf(1.0, 2.0, -2.0))
        val m = Array(3) { i -> DoubleArray(3) { j -> 9.0 * d[i] * d[j] } }
        m[0][0] += 0.1; m[1][1] += 0.1; m[2][2] += 0.1  // small isotropic floor
        val (evals, evecs) = StrokeAnalyzer.jacobiEigenDesc(m)
        assertTrue("largest eigenvalue first", evals[0] >= evals[1] && evals[1] >= evals[2])
        val pc1 = doubleArrayOf(evecs[0][0], evecs[1][0], evecs[2][0])
        val dot = abs(pc1[0] * d[0] + pc1[1] * d[1] + pc1[2] * d[2])
        assertEquals("PC1 aligns with the dominant axis", 1.0, dot, 0.02)
    }
}
