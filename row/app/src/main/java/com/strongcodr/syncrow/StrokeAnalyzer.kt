package com.strongcodr.syncrow

import com.strongcodr.syncrow.model.SensorSyncStatus
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
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
 *   3. Cross-sensor offset: per reference stroke, windowed cross-correlation of the
 *      projected signals (sign-aligned first), Gaussian peak interpolation → ms.
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
 *     after it happens. A display latency, not a change to the number.
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
        var sig: Double = 0.0   // gyro projected onto the current sweep axis
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

        // held / degraded
        private var lastFreshMs = 0L
        var heldMs = 0L; private set
        var degraded = false; private set

        fun reset() {
            buf.clear()
            down = doubleArrayOf(0.0, 0.0, 1.0); sweep = doubleArrayOf(1.0, 0.0, 0.0)
            gMean = doubleArrayOf(0.0, 0.0, 0.0); axisReady = false; sweepEnergy = 0.0; domHz = 0.0
            lastAxisMs = 0L
            stateAbove = false; stateInit = false; prevAboveRaw = false
            prevSig = Double.NaN; prevT = 0L
            lastRawCrossMs = 0L; lastRawCrossUp = false; haveRawCross = false
            catchIsUp = null; gyroAfterUp = 0.0; nAfterUp = 0; gyroAfterDown = 0.0; nAfterDown = 0
            lookaheadUntil = 0L; lastCrossUp = false
            lastCatchMs = 0L; prevCatchMs = 0L; catchCount = 0
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
            if (!axisReady) return null

            // project this sample onto the sweep axis (gyro, mean-removed)
            s.sig = (s.gx - gMean[0]) * sweep[0] + (s.gy - gMean[1]) * sweep[1] + (s.gz - gMean[2]) * sweep[2]

            return detect(s.t, s.sig)
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

            // feather rejection: prefer PC1 if it has an in-band stroke period, else PC2
            val pc1 = doubleArrayOf(evecs[0][0], evecs[1][0], evecs[2][0])
            val pc2 = doubleArrayOf(evecs[0][1], evecs[1][1], evecs[2][1])
            var chosen = pc1
            if (periodOfAxis(gmx, gmy, gmz, pc1) == 0L) {
                if (periodOfAxis(gmx, gmy, gmz, pc2) != 0L) chosen = pc2
            }

            // orthogonalise against gravity, normalise, deterministic sign
            val dot = chosen[0] * dn[0] + chosen[1] * dn[1] + chosen[2] * dn[2]
            var sx = chosen[0] - dot * dn[0]; var sy = chosen[1] - dot * dn[1]; var sz = chosen[2] - dot * dn[2]
            val sn = sqrt(sx * sx + sy * sy + sz * sz)
            if (sn <= EPS) return
            sx /= sn; sy /= sn; sz /= sn
            val sv = doubleArrayOf(sx, sy, sz)
            val ai = if (abs(sx) >= abs(sy) && abs(sx) >= abs(sz)) 0 else if (abs(sy) >= abs(sz)) 1 else 2
            if (sv[ai] < 0) { sv[0] = -sv[0]; sv[1] = -sv[1]; sv[2] = -sv[2] }

            down = dn; sweep = sv; gMean = doubleArrayOf(gmx, gmy, gmz)

            // amplitude (deg/s) + dominant frequency of the projected signal
            var sumSq = 0.0
            for (s in buf) {
                val p = (s.gx - gmx) * sv[0] + (s.gy - gmy) * sv[1] + (s.gz - gmz) * sv[2]
                sumSq += p * p
            }
            sweepEnergy = sqrt(sumSq / n)
            estPeriodMs = periodOfAxis(gmx, gmy, gmz, sv)
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
            // running median + IQR over the trailing window of projected values
            val vals = DoubleArray(buf.size) { buf.elementAt(it).sig }
            if (vals.size < MIN_WINDOW_FOR_MEDIAN) return null
            vals.sort()
            val nn = vals.size
            val median = if (nn % 2 == 1) vals[nn / 2] else (vals[nn / 2 - 1] + vals[nn / 2]) / 2.0
            val q1 = vals[nn / 4]; val q3 = vals[(3 * nn) / 4]
            val h = HYST_FRAC * (q3 - q1)

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
            val refWin = refTrack.resample(t0, t1, XCORR_GRID_HZ) ?: continue
            _lateness[ref] = 0L; _latenessUpdatedMs[ref] = p.tRef; _corr[ref] = 1.0

            val offsets = ArrayList<Double>()
            offsets.add(0.0)
            for ((mac, track) in tracks) {
                if (mac == ref || !isRowing(track)) continue
                var win = track.resample(t0, t1, XCORR_GRID_HZ) ?: continue
                // Sign-align: an arbitrarily-mounted sensor's synthetic axis may be
                // anti-phase to the stroke's. Flip it (portal align_signs) BEFORE the
                // cross-correlation, else the peak lands a half-stroke off. Zero-lag
                // correlation sign, on mean-removed windows.
                if (signOfCorr(refWin, win) < 0) win = DoubleArray(win.size) { -win[it] }
                val est = gaussianLag(refWin, win, XCORR_GRID_HZ, p.halfMs * XCORR_MAXLAG_FRAC / 1000.0)
                    ?: continue
                _corr[mac] = est.rho
                _latenessUpdatedMs[mac] = p.tRef
                val offMs = est.lagS * 1000.0
                offsets.add(offMs)
                // display a MEDIAN of recent trustworthy strokes (like the portal) so one
                // weak-match stroke can't flick the number to a wild value.
                if (est.rho >= MIN_CORR) {
                    val ring = _recent.getOrPut(mac) { ArrayDeque() }
                    ring.addLast(offMs)
                    while (ring.size > RECENT_STROKES) ring.removeFirst()
                    _lateness[mac] = Math.round(median(ring))
                }
            }
        }
    }

    @Synchronized
    fun getLateness(mac: String): Long? = _lateness[mac]

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

    /** Internal state, for diagnostics/replay. */
    @Synchronized
    fun debugState(mac: String): String {
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

    class LagEst(val lagS: Double, val rho: Double)

    companion object {
        private const val EPS = 1e-9

        // frame / detection (mirror portal config + validity)
        private const val WINDOW_MS = 20_000L
        private const val MIN_SAMPLES = 30
        private const val AXIS_REFRESH_MS = 500L
        private const val MIN_WINDOW_FOR_MEDIAN = 10
        private const val PERIOD_HZ = 25.0          // uniform grid for the autocorrelation period
        private const val AUTOCORR_MIN = 0.30       // min autocorrelation to accept a stroke period
        private const val HYST_FRAC = 0.20
        private const val REFRACTORY_FRAC = 0.55
        private const val REFRACTORY_FLOOR_MS = 500L
        private const val GYRO_LOOKAHEAD_MS = 300L
        private const val MIN_GYRO_SAMPLES = 6
        private const val HELD_RUN_FRAC = 0.30
        private const val HELD_FLOOR_MS = 500L

        // cross-sensor
        private const val XCORR_GRID_HZ = 100.0
        private const val XCORR_WINDOW_FRAC = 0.5      // half-window = this * stroke period
        private const val XCORR_MAXLAG_FRAC = 0.60     // max searched lag as frac of half-window
        private const val XCORR_MARGIN_MS = 120L       // wait a touch past the window edge
        private const val MIN_CORR = 0.5
        private const val RECENT_STROKES = 7        // median window for the displayed lateness

        // stroke validity (shared spec)
        private const val BAND_LO = 0.2
        private const val BAND_HI = 0.84
        private const val MIN_SWEEP_ENERGY = 10.0      // deg/s std of the projection

        // status
        private const val STALE_WINDOW_MS = 6000L

        private fun inStrokeBand(hz: Double) = hz in BAND_LO..BAND_HI
        private fun maxL(a: Long, b: Long) = if (a > b) a else b

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
         * Sub-sample lag of `other` vs `ref` by cross-correlation with Gaussian peak
         * interpolation, plus the normalized peak correlation (match confidence).
         * Positive lag => `other` lags (is later than) `ref`. Port of the portal's
         * gaussian_lag; verified by test.
         */
        fun gaussianLag(ref: DoubleArray, other: DoubleArray, fs: Double, maxLagS: Double): LagEst? {
            val n = minOf(ref.size, other.size)
            if (n < 8 || fs <= 0) return null
            var ma = 0.0; var mb = 0.0
            for (i in 0 until n) { ma += ref[i]; mb += other[i] }
            ma /= n; mb /= n
            val a = DoubleArray(n) { ref[it] - ma }
            val b = DoubleArray(n) { other[it] - mb }
            var na = 0.0; var nb = 0.0
            for (i in 0 until n) { na += a[i] * a[i]; nb += b[i] * b[i] }
            na = sqrt(na); nb = sqrt(nb)
            if (na <= EPS || nb <= EPS) return null

            val maxLag = (maxLagS * fs).toInt().coerceIn(1, n - 1)
            var bestLag = 0; var bestCorr = Double.NEGATIVE_INFINITY
            val corrAt = HashMap<Int, Double>()
            for (lag in -maxLag..maxLag) {
                // s(lag) = Σ ref[i] * other[i+lag]; peak lag is POSITIVE when `other`
                // is delayed (later) than `ref` — verified in test.
                var s = 0.0
                for (i in 0 until n) {
                    val j = i + lag
                    if (j in 0 until n) s += a[i] * b[j]
                }
                corrAt[lag] = s
                if (s > bestCorr) { bestCorr = s; bestLag = lag }
            }
            val rho = (bestCorr / (na * nb)).coerceIn(-1.0, 1.0)
            val ym1 = corrAt[bestLag - 1]; val y0 = corrAt[bestLag]; val yp1 = corrAt[bestLag + 1]
            var lag = bestLag.toDouble()
            if (ym1 != null && yp1 != null && ym1 > 0 && y0 != null && y0 > 0 && yp1 > 0) {
                val lm1 = ln(ym1); val l0 = ln(y0); val lp1 = ln(yp1)
                val denom = lm1 - 2 * l0 + lp1
                if (denom != 0.0) lag = bestLag + 0.5 * (lm1 - lp1) / denom
            }
            // convention: + lag of `a` under `b` => other later => positive ms
            return LagEst(lag / fs, rho)
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
