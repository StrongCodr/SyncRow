package com.strongcodr.syncrow

import com.strongcodr.syncrow.model.SensorSyncStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time stroke timing — UNIFIED with the portal's authoritative engine
 * (SyncRow_Portal `srow/analysis`). Same math, made causal for the live tier:
 *
 *   1. Synthetic axis (frame): gravity `down` = mean accel; `sweep` = dominant gyro
 *      rotation axis (PCA over a trailing window), feather-checked, orthogonalised
 *      against gravity, deterministic sign. Gyro projected onto `sweep` = a signed
 *      1-D stroke signal — INDEPENDENT of how the sensor is mounted on the oar.
 *   2. Catch detection: running-median crossing + Schmitt hysteresis + refractory.
 *   3. Cross-sensor offset: per reference stroke, the difference in FUNDAMENTAL PHASE
 *      of the sign-aligned projected signals (a single-bin DFT at the stroke rate).
 *      Harmonic-immune — a plain cross-correlation locks onto the wrong peak when the
 *      gyro waveform is harmonic-rich (which real rowing is).
 *
 * Reference = the STROKE seat = HIGHEST seat number; it reads 0, everyone else is
 * measured against it.
 *
 * REAL-TIME ADJUSTMENTS vs the batch portal (kept minimal, non-material):
 *   - PCA runs over a trailing WINDOW_MS window, not the whole piece. The stroke
 *     axis is ~constant over a piece, so the axis is the same.
 *   - Each reference stroke's offset uses a window CENTRED on the catch (half a
 *     stroke either side), identical to the portal — but we must wait until that
 *     forward half has arrived, so a stroke's offset appears ~half a stroke (~1.2s)
 *     after it happens. A display latency, not a change to the number. (Consequence:
 *     the final ~1 stroke of a piece never gets its forward half, so it's absent from
 *     the running median — invisible on a live display, accepted; the portal recompute
 *     is authoritative for the full piece.)
 *
 * Validated against synthetic ground truth (StrokeAnalyzerTest) the same way the
 * portal is: inject known per-seat offsets through arbitrary mountings, recover them.
 *
 * Thread safety: all public methods are synchronized (called from the recording
 * service IO loop and the main thread).
 */
class StrokeAnalyzer {

    // ─────────────────────────── per-sensor track ───────────────────────────

    private class Sample(
        val t: Long,
        val ax: Double, val ay: Double, val az: Double,
        val gx: Double, val gy: Double, val gz: Double,
    ) {
        var sig: Double = 0.0    // gyro projected onto the current sweep axis (raw, for the phase/offset)
        var sigS: Double = 0.0   // smoothed projection, for catch DETECTION only
    }

    private class Track(val seatIndex: Int) {
        val buf = ArrayDeque<Sample>()          // trailing window of FRESH samples

        // synthetic frame
        var down = doubleArrayOf(0.0, 0.0, 1.0)
        var sweep = doubleArrayOf(1.0, 0.0, 0.0)
        var gMean = doubleArrayOf(0.0, 0.0, 0.0) // gyro mean (removed before projection)
        var axisReady = false
        var sweepEnergy = 0.0                    // std of projection (deg/s) — rowing gate
        var domHz = 0.0
        var estPeriodMs = 0L      // robust stroke period (autocorrelation); 0 = none
        private var lastAxisMs = 0L

        // cached median + hysteresis band (refreshed every STATS_REFRESH samples, not
        // every sample — they drift slowly, and full-sorting the buffer per sample is the
        // hot-path cost with many sensors)
        private var cMedian = 0.0
        private var cHyst = 0.0
        private var statsCountdown = 0

        // catch detection state (Schmitt on the projected signal)
        private var stateAbove = false
        private var stateInit = false
        private var prevAboveRaw = false
        private var prevSig = Double.NaN
        private var prevT = 0L
        private var lastRawCrossMs = 0L
        private var lastRawCrossUp = false
        private var haveRawCross = false

        // catch phase (which crossing is the catch: the one with more drive-gyro after)
        private var catchIsUp: Boolean? = null
        private var gyroAfterUp = 0.0; private var nAfterUp = 0
        private var gyroAfterDown = 0.0; private var nAfterDown = 0
        private var lookaheadUntil = 0L
        private var lastCrossUp = false

        var lastCatchMs = 0L; private set
        var prevCatchMs = 0L; private set
        var catchCount = 0; private set
        val periodMs: Long
            get() = if (prevCatchMs > 0 && lastCatchMs > prevCatchMs) lastCatchMs - prevCatchMs else 0L

        // Recent catch times → a FALLBACK period (median inter-catch interval) used only
        // when the autocorrelation can't lock (very erratic / feather-heavy motion). This
        // is what keeps the app from going fully dark: catches are detected even when the
        // spectral period isn't, so we can still show a rate/count and attempt an offset.
        private val catchTimes = ArrayDeque<Long>()
        fun recordCatch(t: Long) {
            catchTimes.addLast(t)
            while (catchTimes.size > CATCH_HISTORY) catchTimes.removeFirst()
        }
        fun catchPeriodMs(): Long {
            if (catchTimes.size < 3) return 0L
            val d = ArrayList<Double>(catchTimes.size - 1)
            var prev = -1L
            for (c in catchTimes) { if (prev > 0) d.add((c - prev).toDouble()); prev = c }
            val med = median(d).toLong()
            val loP = (1000.0 / BAND_HI).toLong(); val hiP = (1000.0 / BAND_LO).toLong()
            return if (med in loP..hiP) med else 0L
        }

        // Displayed stroke count/rate come from the ROBUST period (estPeriodMs), not from
        // counting spike-prone crossings: integrate rate over rowing time. domHz is
        // identical across seats and dead-on where catch counts disagree.
        var strokeAccum = 0.0     // fractional strokes accumulated while rowing
        private var prevFreshT = 0L
        val strokeCount: Int get() = strokeAccum.toInt()
        val spm: Int get() = if (estPeriodMs > 0) (60000.0 / estPeriodMs).toInt() else 0

        fun accumulateStrokes(now: Long) {
            if (estPeriodMs > 0 && sweepEnergy >= MIN_SWEEP_ENERGY && prevFreshT > 0L) {
                strokeAccum += (now - prevFreshT).toDouble() / estPeriodMs
            }
            prevFreshT = now
        }

        // held / degraded
        private var lastFreshMs = 0L
        var heldMs = 0L; private set
        var degraded = false; private set

        fun reset() {
            buf.clear()
            down = doubleArrayOf(0.0, 0.0, 1.0); sweep = doubleArrayOf(1.0, 0.0, 0.0)
            gMean = doubleArrayOf(0.0, 0.0, 0.0); axisReady = false; sweepEnergy = 0.0; domHz = 0.0
            estPeriodMs = 0L; lastAxisMs = 0L
            cMedian = 0.0; cHyst = 0.0; statsCountdown = 0
            stateAbove = false; stateInit = false; prevAboveRaw = false
            prevSig = Double.NaN; prevT = 0L
            lastRawCrossMs = 0L; lastRawCrossUp = false; haveRawCross = false
            catchIsUp = null; gyroAfterUp = 0.0; nAfterUp = 0; gyroAfterDown = 0.0; nAfterDown = 0
            lookaheadUntil = 0L; lastCrossUp = false
            lastCatchMs = 0L; prevCatchMs = 0L; catchCount = 0; catchTimes.clear()
            strokeAccum = 0.0; prevFreshT = 0L
            lastFreshMs = 0L; heldMs = 0L; degraded = false
        }

        /** Held-sample gate: a non-fresh tick is a zero-order-hold repeat; don't feed
         *  it to detection, and flag DEGRADED if fresh data has been missing too long. */
        fun markHeld(now: Long) {
            if (lastFreshMs > 0L) heldMs = now - lastFreshMs
            val thresh = maxL(HELD_FLOOR_MS, (periodMs * HELD_RUN_FRAC).toLong())
            degraded = heldMs > thresh
        }

        /** Feed a fresh sample: buffer it, refresh the axis, project, detect a catch. */
        fun onFresh(s: Sample): Long? {
            lastFreshMs = s.t; heldMs = 0L; degraded = false
            buf.addLast(s)
            while (buf.isNotEmpty() && s.t - buf.first().t > WINDOW_MS) buf.removeFirst()

            recomputeAxisIfDue(s.t)
            accumulateStrokes(s.t)          // count/rate from the robust period, every fresh sample
            if (!axisReady) return null

            // project this sample onto the sweep axis (gyro, mean-removed)
            s.sig = (s.gx - gMean[0]) * sweep[0] + (s.gy - gMean[1]) * sweep[1] + (s.gz - gMean[2]) * sweep[2]

            // Pre-detection smoothing — the portal does this to "kill feather/noise
            // wiggles" (detect.py). Without it, the sharp feather / hand-reversal spikes
            // of a sculling stroke clear the threshold and get counted as extra catches.
            // Causal moving average over ~SMOOTH_FRAC of a stroke period. The offset path
            // keeps the RAW sig (the phase estimator wants the full oscillation).
            val smoothMs = if (estPeriodMs > 0) (SMOOTH_FRAC * estPeriodMs).toLong().coerceAtLeast(1L)
            else DEFAULT_SMOOTH_MS
            val lo = s.t - smoothMs
            var sum = 0.0; var cnt = 0
            for (i in buf.indices.reversed()) {
                val e = buf[i]
                if (e.t < lo) break
                sum += e.sig; cnt++
            }
            s.sigS = if (cnt > 0) sum / cnt else s.sig

            return detect(s.t, s.sigS)
        }

        private fun recomputeAxisIfDue(now: Long) {
            if (buf.size < MIN_SAMPLES) return
            if (axisReady && now - lastAxisMs < AXIS_REFRESH_MS) return
            lastAxisMs = now

            val n = buf.size
            // gravity = mean accel
            var mx = 0.0; var my = 0.0; var mz = 0.0
            var gmx = 0.0; var gmy = 0.0; var gmz = 0.0
            for (s in buf) {
                mx += s.ax; my += s.ay; mz += s.az
                gmx += s.gx; gmy += s.gy; gmz += s.gz
            }
            mx /= n; my /= n; mz /= n; gmx /= n; gmy /= n; gmz /= n
            val gn = sqrt(mx * mx + my * my + mz * mz)
            val dn = if (gn > EPS) doubleArrayOf(mx / gn, my / gn, mz / gn) else doubleArrayOf(0.0, 0.0, 1.0)

            // gyro covariance (centered), accumulated in scalars
            var c00 = 0.0; var c01 = 0.0; var c02 = 0.0; var c11 = 0.0; var c12 = 0.0; var c22 = 0.0
            for (s in buf) {
                val x = s.gx - gmx; val y = s.gy - gmy; val z = s.gz - gmz
                c00 += x * x; c01 += x * y; c02 += x * z
                c11 += y * y; c12 += y * z; c22 += z * z
            }
            val nD = n.toDouble()
            val c = arrayOf(
                doubleArrayOf(c00 / nD, c01 / nD, c02 / nD),
                doubleArrayOf(c01 / nD, c11 / nD, c12 / nD),
                doubleArrayOf(c02 / nD, c12 / nD, c22 / nD),
            )

            val (evals, evecs) = jacobiEigenDesc(c)
            if (evals[0] <= EPS) return

            // feather rejection: prefer PC1 if it has an in-band stroke period, else PC2.
            // Compute the chosen axis's period ONCE and reuse it (sweep ≈ chosen after
            // orthogonalisation, so its period is the same) — avoids a 3rd periodOfAxis.
            val pc1 = doubleArrayOf(evecs[0][0], evecs[1][0], evecs[2][0])
            val pc2 = doubleArrayOf(evecs[0][1], evecs[1][1], evecs[2][1])
            var chosen = pc1
            var chosenPeriod = periodOfAxis(gmx, gmy, gmz, pc1)
            if (chosenPeriod == 0L) {
                val p2 = periodOfAxis(gmx, gmy, gmz, pc2)
                if (p2 != 0L) { chosen = pc2; chosenPeriod = p2 }
            }

            // orthogonalise against gravity, normalise
            val dot = chosen[0] * dn[0] + chosen[1] * dn[1] + chosen[2] * dn[2]
            var sx = chosen[0] - dot * dn[0]; var sy = chosen[1] - dot * dn[1]; var sz = chosen[2] - dot * dn[2]
            val sn = sqrt(sx * sx + sy * sy + sz * sz)
            if (sn <= EPS) return
            sx /= sn; sy /= sn; sz /= sn
            val sv = doubleArrayOf(sx, sy, sz)
            // SIGN CONTINUITY: keep the sign consistent with the PREVIOUS sweep so the
            // projected signal never inverts between recomputes (an inversion mid-stream
            // corrupts the running detection state -> spurious/missed catches). Only the
            // very first axis uses the deterministic (largest-component-positive) rule.
            if (axisReady) {
                if (sv[0] * sweep[0] + sv[1] * sweep[1] + sv[2] * sweep[2] < 0) {
                    sv[0] = -sv[0]; sv[1] = -sv[1]; sv[2] = -sv[2]
                }
            } else {
                val ai = if (abs(sx) >= abs(sy) && abs(sx) >= abs(sz)) 0 else if (abs(sy) >= abs(sz)) 1 else 2
                if (sv[ai] < 0) { sv[0] = -sv[0]; sv[1] = -sv[1]; sv[2] = -sv[2] }
            }

            down = dn; sweep = sv; gMean = doubleArrayOf(gmx, gmy, gmz)

            var sumSq = 0.0
            for (s in buf) {
                val p = (s.gx - gmx) * sv[0] + (s.gy - gmy) * sv[1] + (s.gz - gmz) * sv[2]
                sumSq += p * p
            }
            sweepEnergy = sqrt(sumSq / n)
            // Autocorrelation is authoritative when it locks (prevents catch doubling);
            // fall back to the median inter-catch interval only when it can't — so an
            // erratic/feather-heavy seat still gets a period instead of going dark.
            estPeriodMs = if (chosenPeriod > 0L) chosenPeriod else catchPeriodMs()
            domHz = if (estPeriodMs > 0) 1000.0 / estPeriodMs else 0.0
            axisReady = true
        }

        /** Stroke period (ms) of the gyro projected on `axis`, via AUTOCORRELATION —
         *  the FFT-free equivalent of the portal's spectral peak. Robust to harmonics
         *  and noise (crossing-counting is not: it locks onto the 2× harmonic and reads
         *  0.44 Hz as ~0.9 Hz). Projects onto the axis, resamples to a uniform grid,
         *  and returns the lag of the strongest autocorrelation peak within the stroke
         *  band. 0 if there is no clear in-band periodicity. */
        private fun periodOfAxis(gmx: Double, gmy: Double, gmz: Double, axis: DoubleArray): Long {
            val arr = buf.toList()
            if (arr.size < 16) return 0
            val t0 = arr.first().t; val t1 = arr.last().t
            if (t1 - t0 < 3000) return 0                 // need a few seconds
            val m = ((t1 - t0) / 1000.0 * PERIOD_HZ).toInt()
            if (m < 32) return 0
            val x = DoubleArray(m)
            var j = 0
            for (i in 0 until m) {
                val tt = t0 + i * 1000.0 / PERIOD_HZ
                while (j < arr.size - 1 && arr[j + 1].t < tt) j++
                val a = arr[j]; val b = arr[if (j + 1 < arr.size) j + 1 else j]
                val pa = (a.gx - gmx) * axis[0] + (a.gy - gmy) * axis[1] + (a.gz - gmz) * axis[2]
                val pb = (b.gx - gmx) * axis[0] + (b.gy - gmy) * axis[1] + (b.gz - gmz) * axis[2]
                x[i] = if (b.t == a.t) pa else pa + (tt - a.t) / (b.t - a.t) * (pb - pa)
            }
            var mean = 0.0; for (v in x) mean += v; mean /= m
            for (i in x.indices) x[i] -= mean
            var zero = 0.0; for (v in x) zero += v * v
            if (zero < EPS) return 0
            val minLag = (PERIOD_HZ / BAND_HI).toInt()
            val maxLag = minOf((PERIOD_HZ / BAND_LO).toInt(), m - 1)
            var bestLag = -1; var best = 0.0
            for (lag in minLag..maxLag) {
                var s = 0.0
                for (i in 0 until m - lag) s += x[i] * x[i + lag]
                val cc = s / zero
                if (cc > best) { best = cc; bestLag = lag }
            }
            if (bestLag < 0 || best < AUTOCORR_MIN) return 0
            return (bestLag * 1000.0 / PERIOD_HZ).toLong()
        }

        /** Median-crossing catch detection on the projected signal. Same structure as
         *  the portal detector: raw median crossing gives the accurate TIME; a Schmitt
         *  trigger (dead-band scaled to the stroke swing) decides WHETHER to emit;
         *  refractory rejects doubles. */
        private fun detect(t: Long, sig: Double): Long? {
            if (buf.size < MIN_WINDOW_FOR_MEDIAN) return null
            // running median + IQR, refreshed every STATS_REFRESH samples (they drift
            // slowly) instead of a full sort every sample.
            if (statsCountdown <= 0) {
                val vals = DoubleArray(buf.size) { buf.elementAt(it).sigS }
                vals.sort()
                val nn = vals.size
                cMedian = if (nn % 2 == 1) vals[nn / 2] else (vals[nn / 2 - 1] + vals[nn / 2]) / 2.0
                cHyst = HYST_FRAC * (vals[(3 * nn) / 4] - vals[nn / 4])
                statsCountdown = STATS_REFRESH
            }
            statsCountdown--
            val median = cMedian; val h = cHyst

            // gyro-after accumulation to fix catch phase
            if (t <= lookaheadUntil) {
                val mag = abs(sig)
                if (lastCrossUp) { gyroAfterUp += mag; nAfterUp++ } else { gyroAfterDown += mag; nAfterDown++ }
            }

            if (prevSig.isNaN() || prevT == 0L || !stateInit) {
                stateAbove = sig > median; prevAboveRaw = sig > median; stateInit = true
                prevSig = sig; prevT = t; return null
            }

            // (1) raw crossing → accurate time (prev & cur straddle the median)
            val aboveRaw = sig > median
            if (aboveRaw != prevAboveRaw) {
                val d = sig - prevSig
                if (abs(d) > 1e-9) {
                    val frac = ((median - prevSig) / d).coerceIn(0.0, 1.0)
                    lastRawCrossMs = prevT + (frac * (t - prevT)).toLong()
                    lastRawCrossUp = aboveRaw; haveRawCross = true
                }
            }
            prevAboveRaw = aboveRaw

            // (2) Schmitt confirmation
            val newAbove = when { sig > median + h -> true; sig < median - h -> false; else -> stateAbove }
            val flip = newAbove != stateAbove
            stateAbove = newAbove
            prevSig = sig; prevT = t
            if (!flip || !haveRawCross || lastRawCrossUp != newAbove) return null

            val crossUp = newAbove
            val crossMs = lastRawCrossMs
            haveRawCross = false
            lookaheadUntil = t + GYRO_LOOKAHEAD_MS
            lastCrossUp = crossUp

            if (catchIsUp == null && nAfterUp >= MIN_GYRO_SAMPLES && nAfterDown >= MIN_GYRO_SAMPLES) {
                catchIsUp = (gyroAfterUp / nAfterUp) > (gyroAfterDown / nAfterDown)
            }
            if (catchIsUp == null) return null
            val isCatch = if (catchIsUp == true) crossUp else !crossUp
            if (!isCatch) return null

            // refractory from the ROBUST (autocorrelation) period, not the catch-to-catch
            // interval — bootstrapping from possibly-doubled catches would keep doubling.
            val basePeriod = if (estPeriodMs > 0) estPeriodMs else periodMs
            val refractory = if (basePeriod > 0) maxL(REFRACTORY_FLOOR_MS, (basePeriod * REFRACTORY_FRAC).toLong())
            else REFRACTORY_FLOOR_MS
            if (lastCatchMs > 0 && crossMs - lastCatchMs < refractory) return null

            prevCatchMs = lastCatchMs; lastCatchMs = crossMs; catchCount++
            recordCatch(crossMs)     // feed the fallback-period estimator
            return crossMs
        }

        /** Uniformly-resampled projected signal over [t0,t1] (linear interp), for x-corr. */
        fun resample(t0: Long, t1: Long, hz: Double): DoubleArray? {
            if (buf.size < 2 || t1 <= t0) return null
            val step = 1000.0 / hz
            val nOut = ((t1 - t0) / step).toInt()
            if (nOut < 8) return null
            val out = DoubleArray(nOut)
            val arr = buf.toList()
            var j = 0
            for (i in 0 until nOut) {
                val tt = t0 + i * step
                while (j < arr.size - 1 && arr[j + 1].t < tt) j++
                val a = arr[j]; val b = arr[if (j + 1 < arr.size) j + 1 else j]
                out[i] = if (b.t == a.t) a.sig else {
                    val f = (tt - a.t) / (b.t - a.t)
                    a.sig + f * (b.sig - a.sig)
                }
            }
            return out
        }
    }

    // ─────────────────────────── cross-sensor state ─────────────────────────

    private val tracks = HashMap<String, Track>()
    private val macToSeat = HashMap<String, Int>()
    private var referenceMac: String? = null

    private val _lateness = HashMap<String, Long>()
    private val _latenessUpdatedMs = HashMap<String, Long>()
    private val _corr = HashMap<String, Double>()
    private val _recent = HashMap<String, ArrayDeque<Double>>()  // recent offsets for a stable median
    private var lastSampleMs = 0L

    /** Reference catches awaiting their forward half-window before offsets compute. */
    private class Pending(val tRef: Long, val halfMs: Long)
    private val pending = ArrayDeque<Pending>()

    // ─────────────────────────────── public API ─────────────────────────────

    @Synchronized
    fun addSensor(mac: String, seatIndex: Int) {
        tracks[mac] = Track(seatIndex)
        macToSeat[mac] = seatIndex
        // reference = STROKE = highest seat number (matches portal + the UI label)
        if (referenceMac == null || seatIndex > (macToSeat[referenceMac] ?: Int.MIN_VALUE)) {
            referenceMac = mac
        }
    }

    /**
     * Feed a sample (call at the AHRS rate). `fresh` = a new BLE sample arrived since
     * the last call (held repeats are skipped). Uses ACCEL + GYRO (no Euler angles).
     * Returns this sensor's current lateness vs stroke in ms, or null.
     */
    @Synchronized
    fun onSample(
        mac: String, timeMs: Long, fresh: Boolean,
        ax: Float, ay: Float, az: Float, wx: Float, wy: Float, wz: Float,
    ): Long? {
        if (timeMs > lastSampleMs) lastSampleMs = timeMs
        val track = tracks[mac] ?: return null

        if (!fresh) {
            track.markHeld(timeMs)
            if (track.degraded) { _lateness.remove(mac); _latenessUpdatedMs.remove(mac); _recent.remove(mac) }
            drainPending()
            return null
        }

        val catch = track.onFresh(
            Sample(timeMs, ax.toDouble(), ay.toDouble(), az.toDouble(),
                wx.toDouble(), wy.toDouble(), wz.toDouble())
        )
        if (track.degraded) { _lateness.remove(mac); _latenessUpdatedMs.remove(mac); _recent.remove(mac) }

        if (catch != null && mac == referenceMac && isRowing(track)) {
            val period = if (track.estPeriodMs > 0) track.estPeriodMs else track.periodMs
            if (period in 600..6000) pending.addLast(Pending(catch, (period * XCORR_WINDOW_FRAC).toLong()))
        }
        drainPending()
        return _lateness[mac]
    }

    /** Compute offsets for any reference stroke whose forward half-window has arrived. */
    private fun drainPending() {
        val ref = referenceMac ?: return
        val refTrack = tracks[ref] ?: return
        while (pending.isNotEmpty()) {
            val p = pending.first()
            if (lastSampleMs < p.tRef + p.halfMs + XCORR_MARGIN_MS) break   // wait for the future half
            pending.removeFirst()

            val t0 = p.tRef - p.halfMs; val t1 = p.tRef + p.halfMs
            val f0 = 1000.0 / (2.0 * p.halfMs)                 // fundamental Hz (period = 2*half)
            val refWin = refTrack.resample(t0, t1, XCORR_GRID_HZ) ?: continue
            val refPhase = fundamentalPhase(refWin, f0, XCORR_GRID_HZ) ?: continue
            _lateness[ref] = 0L; _latenessUpdatedMs[ref] = p.tRef; _corr[ref] = 1.0

            for ((mac, track) in tracks) {
                if (mac == ref || !isRowing(track)) continue
                var win = track.resample(t0, t1, XCORR_GRID_HZ) ?: continue
                // Sign-align first: an arbitrarily-mounted sensor's synthetic axis may be
                // anti-phase to the stroke's, which would shift the fundamental phase by
                // pi (a half-period offset error). Flip via the zero-lag correlation sign.
                if (signOfCorr(refWin, win) < 0) win = DoubleArray(win.size) { -win[it] }
                val seatPhase = fundamentalPhase(win, f0, XCORR_GRID_HZ) ?: continue
                // Offset from the FUNDAMENTAL phase difference — harmonic-immune. A strong
                // 2x harmonic makes the raw waveform double-peaked and lets a plain x-corr
                // lock onto the wrong peak (a confident-but-wrong lag); the fundamental
                // phase ignores harmonics entirely. Phase wraps at one period, so a real
                // (sub-period) crew offset is unambiguous.
                var dphi = seatPhase.phase - refPhase.phase
                while (dphi > PI) dphi -= 2.0 * PI
                while (dphi < -PI) dphi += 2.0 * PI
                val offMs = dphi / (2.0 * PI * f0) * 1000.0
                // confidence = how well each window is a clean fundamental oscillation
                val rho = minOf(refPhase.coherence, seatPhase.coherence)
                _corr[mac] = rho
                _latenessUpdatedMs[mac] = p.tRef
                if (rho >= MIN_CORR) {
                    val ring = _recent.getOrPut(mac) { ArrayDeque() }
                    ring.addLast(offMs)
                    while (ring.size > RECENT_STROKES) ring.removeFirst()
                    _lateness[mac] = Math.round(median(ring))  // stable median, like the portal
                }
            }
        }
    }

    @Synchronized
    fun getLateness(mac: String): Long? = _lateness[mac]

    /** Displayed stroke count for a seat — from the robust period, immune to the
     *  feather/reversal spikes that make crossing-counting double up. */
    @Synchronized
    fun getStrokeCount(mac: String): Int = tracks[mac]?.strokeCount ?: 0

    /** Displayed stroke rate (spm) for a seat — 60000 / robust period, 0 if not rowing. */
    @Synchronized
    fun getSpm(mac: String): Int = tracks[mac]?.spm ?: 0

    /**
     * Per-seat quality (shared with the portal): CALIBRATING until the axis + a first
     * paired offset exist; DEGRADED_SIGNAL if the sensor is held; OK if a recent
     * offset with a good cross-correlation match; STALE otherwise.
     */
    @Synchronized
    fun getStatus(mac: String): SensorSyncStatus {
        val track = tracks[mac] ?: return SensorSyncStatus.CALIBRATING
        if (!track.axisReady) return SensorSyncStatus.CALIBRATING
        if (track.degraded) return SensorSyncStatus.DEGRADED_SIGNAL
        val updated = _latenessUpdatedMs[mac] ?: 0L
        if (updated <= 0L) return if (mac == referenceMac) SensorSyncStatus.CALIBRATING else SensorSyncStatus.STALE
        val fresh = lastSampleMs - updated <= STALE_WINDOW_MS
        val matched = mac == referenceMac || (_corr[mac] ?: 0.0) >= MIN_CORR
        return if (fresh && matched) SensorSyncStatus.OK else SensorSyncStatus.STALE
    }

    @Synchronized
    fun isCalibrated(mac: String): Boolean = tracks[mac]?.axisReady == true

    /** Internal state, for diagnostics/replay/tests only (not part of the app API). */
    @Synchronized
    internal fun debugState(mac: String): String {
        val t = tracks[mac] ?: return "no track"
        return "axis=${t.axisReady} sweepE=${"%.1f".format(t.sweepEnergy)} domHz=${"%.3f".format(t.domHz)} " +
            "catches=${t.catchCount} rowing=${isRowing(t)} buf=${t.buf.size} " +
            "ref=${mac == referenceMac} pending=${pending.size} corr=${_corr[mac]?.let { "%.2f".format(it) } ?: "-"}"
    }

    @Synchronized
    fun reset() {
        tracks.values.forEach { it.reset() }
        _lateness.clear(); _latenessUpdatedMs.clear(); _corr.clear(); _recent.clear(); pending.clear(); lastSampleMs = 0L
    }

    @Synchronized
    fun clear() {
        tracks.clear(); macToSeat.clear(); referenceMac = null
        _lateness.clear(); _latenessUpdatedMs.clear(); _corr.clear(); _recent.clear(); pending.clear(); lastSampleMs = 0L
    }

    private fun isRowing(t: Track): Boolean =
        t.axisReady && inStrokeBand(t.domHz) && t.sweepEnergy >= MIN_SWEEP_ENERGY

    // ─────────────────────────────── math ───────────────────────────────────

    companion object {
        private const val EPS = 1e-9

        // ─── SHARED SPEC — these MUST match the portal (SyncRow_Portal srow/analysis
        //     config.py + validity.py). If you change one, change it there too, and vice
        //     versa; drift here silently makes the phone show strokes the portal drops. ──
        private const val BAND_LO = 0.2                // validity.STROKE_BAND_HZ
        private const val BAND_HI = 0.84               // validity.STROKE_BAND_HZ
        private const val MIN_SWEEP_ENERGY = 10.0      // validity.MIN_SWEEP_ENERGY_DEG_S
        private const val REFRACTORY_FRAC = 0.55       // config.refractory_frac
        private const val HELD_RUN_FRAC = 0.30         // config.max_held_run_frac
        private const val XCORR_WINDOW_FRAC = 0.5      // config.xcorr_window_frac (half-window = this * period)
        private const val SMOOTH_FRAC = 1.0 / 6.0      // config.smooth_frac (pre-detection smoothing window)

        // ─── PHONE-ONLY — real-time tier has no portal equivalent (the portal is batch). ──
        private const val WINDOW_MS = 20_000L          // trailing PCA/detection window
        private const val MIN_SAMPLES = 30             // min fresh samples before an axis
        private const val AXIS_REFRESH_MS = 500L       // recompute the synthetic axis this often
        private const val MIN_WINDOW_FOR_MEDIAN = 10
        private const val STATS_REFRESH = 8            // recompute median/IQR every N samples
        private const val CATCH_HISTORY = 7            // catches kept for the fallback median period
        private const val DEFAULT_SMOOTH_MS = 150L     // smoothing window before a period is known
        private const val PERIOD_HZ = 25.0             // uniform grid for the autocorrelation period
        private const val AUTOCORR_MIN = 0.30          // min autocorrelation to accept a stroke period
        private const val HYST_FRAC = 0.20             // Schmitt band as a fraction of the IQR
        private const val REFRACTORY_FLOOR_MS = 500L
        private const val GYRO_LOOKAHEAD_MS = 300L
        private const val MIN_GYRO_SAMPLES = 6
        private const val HELD_FLOOR_MS = 500L
        private const val XCORR_GRID_HZ = 100.0        // resample grid for the phase estimate
        private const val XCORR_MARGIN_MS = 120L       // wait a touch past the window edge
        // NOTE: the phone gates on fundamental COHERENCE (energy fraction in f0), which is
        // analogous to but NOT the same quantity as the portal's x-corr rho (config.min_corr).
        // Phone-tuned; do not assume it must equal the portal value.
        private const val MIN_CORR = 0.5
        private const val RECENT_STROKES = 7           // median window for the displayed lateness
        private const val STALE_WINDOW_MS = 6000L      // no paired offset within this -> STALE

        private fun inStrokeBand(hz: Double) = hz in BAND_LO..BAND_HI
        private fun maxL(a: Long, b: Long) = if (a > b) a else b

        class Phase(val phase: Double, val coherence: Double)

        /** Fundamental phase of `x` (sampled at `hz`) at frequency `f0`, via a single-bin
         *  DFT (Goertzel-style). `coherence` is the share of the signal that IS that
         *  fundamental (amplitude/RMS, 0..1) — the confidence that this window is a clean
         *  stroke oscillation. Harmonic content contributes to RMS but not to the f0 bin,
         *  so a harmonic-rich signal has a well-defined fundamental phase and lower
         *  coherence — exactly what we want. */
        fun fundamentalPhase(x: DoubleArray, f0: Double, hz: Double): Phase? {
            val n = x.size
            if (n < 8 || f0 <= 0) return null
            var mean = 0.0; for (v in x) mean += v; mean /= n
            var re = 0.0; var im = 0.0; var ss = 0.0
            for (k in 0 until n) {
                val ang = 2.0 * PI * f0 * k / hz
                val v = x[k] - mean
                re += v * cos(ang); im += v * sin(ang); ss += v * v
            }
            if (ss < EPS) return null
            val amp = 2.0 * sqrt(re * re + im * im) / n            // fundamental amplitude
            val rms = sqrt(ss / n)
            val coh = (amp / (rms * sqrt(2.0))).coerceIn(0.0, 1.0) // 1 for a pure sinusoid
            return Phase(atan2(im, re), coh)
        }

        fun median(xs: Collection<Double>): Double {
            val s = xs.sorted(); val n = s.size
            return when { n == 0 -> 0.0; n % 2 == 1 -> s[n / 2]; else -> (s[n / 2 - 1] + s[n / 2]) / 2.0 }
        }

        /** Zero-lag correlation sign between two windows (mean-removed). < 0 = anti-phase. */
        fun signOfCorr(a: DoubleArray, b: DoubleArray): Double {
            val n = minOf(a.size, b.size)
            if (n == 0) return 1.0
            var ma = 0.0; var mb = 0.0
            for (i in 0 until n) { ma += a[i]; mb += b[i] }
            ma /= n; mb /= n
            var s = 0.0
            for (i in 0 until n) s += (a[i] - ma) * (b[i] - mb)
            return if (s < 0) -1.0 else 1.0
        }


        /**
         * Eigen-decomposition of a symmetric 3×3 matrix via cyclic Jacobi rotations.
         * Returns (eigenvalues desc, eigenvectors as columns evecs[row][col]).
         */
        fun jacobiEigenDesc(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
            val a = Array(3) { i -> m[i].copyOf() }
            val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
            for (sweep in 0 until 50) {
                val off = abs(a[0][1]) + abs(a[0][2]) + abs(a[1][2])
                if (off < 1e-14) break
                for (p in 0 until 2) for (q in p + 1 until 3) {
                    val apq = a[p][q]
                    if (abs(apq) < 1e-18) continue
                    val theta = (a[q][q] - a[p][p]) / (2.0 * apq)
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0); val s = t * c
                    val app = a[p][p]; val aqq = a[q][q]
                    a[p][p] = app - t * apq; a[q][q] = aqq + t * apq
                    a[p][q] = 0.0; a[q][p] = 0.0
                    for (k in 0 until 3) if (k != p && k != q) {
                        val akp = a[k][p]; val akq = a[k][q]
                        a[k][p] = c * akp - s * akq; a[p][k] = a[k][p]
                        a[k][q] = s * akp + c * akq; a[q][k] = a[k][q]
                    }
                    for (k in 0 until 3) {
                        val vkp = v[k][p]; val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
            val ev = doubleArrayOf(a[0][0], a[1][1], a[2][2])
            val order = (0..2).sortedByDescending { ev[it] }
            val evals = DoubleArray(3) { ev[order[it]] }
            val evecs = Array(3) { r -> DoubleArray(3) { c -> v[r][order[c]] } }
            return evals to evecs
        }
    }
}
