package com.strongcodr.syncrow

import com.strongcodr.syncrow.model.SensorSyncStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time catch detection and inter-sensor lateness computation.
 *
 * Each sensor gets its own [SensorCalibrator] that auto-detects the dominant
 * orientation axis (pitch, roll, or yaw — whichever has the largest swing)
 * and performs median-crossing interpolation for sub-sample catch timing.
 *
 * Once two or more sensors have reported a catch in the same stroke cycle,
 * the analyzer computes Δt (lateness) relative to the reference sensor —
 * the stroke rower, i.e. the HIGHEST seat number (bow is seat 1). Stroke sets
 * the rhythm, so it reads 0 and every other seat's lateness is measured to it.
 *
 * PER-SEAT QUALITY GATING (shared spec with the portal, RESEARCH.md §8.5): every
 * seat is judged on its own acquisition and pairing. A seat that spaces out —
 * BLE stalls and the sampling loop re-writes the last value (zero-order-hold) —
 * is flagged [SensorSyncStatus.DEGRADED_SIGNAL] and its stale lateness suppressed,
 * while every other seat keeps updating. Held samples (`fresh == false`) are never
 * fed into detection, so a flat plateau can't corrupt the median or invent catches.
 *
 * Thread safety: all public methods are synchronized. The analyzer is called
 * from [IntervalRecordingService]'s IO coroutine (onSample) and the main
 * thread (addSensor, reset, clear).
 */
class StrokeAnalyzer {

    /** Per-sensor state for calibration and catch detection. */
    private class SensorCalibrator(val mac: String, val seatIndex: Int) {

        enum class Channel { PITCH, ROLL, YAW }

        // ── Channel selection via Welford's online variance ──

        private var calibSamples = 0
        private var pitchMean = 0.0; private var pitchM2 = 0.0
        private var rollMean = 0.0;  private var rollM2 = 0.0
        private var yawMean = 0.0;   private var yawM2 = 0.0

        var selectedChannel: Channel? = null
            private set

        // ── Continuous recalibration ──
        // After initial selection, keep tracking variance with exponential
        // moving averages.  If another channel's variance exceeds the current
        // one by >2× for RECALIB_SAMPLES consecutive samples, switch.
        private var emaVariancePitch = 0.0
        private var emaVarianceRoll = 0.0
        private var emaVarianceYaw = 0.0
        private var recalibCounter = 0

        // ── Median-crossing state ──

        /** Running sorted list of window ids (by their smoothed value) for O(log n)
         *  median lookup. Ids (not floats) so removal has no Float-equality problem. */
        private val sortedIndices = mutableListOf<Int>()

        /** Monotonically increasing insert counter — each sample gets a unique id. */
        private var insertCounter = 0

        /** Map from insert-counter id to the smoothed value, for sorting. */
        private val idToValue = mutableMapOf<Int, Float>()

        /** Map from insert-counter id to sample time — the median window is evicted
         *  by AGE (WINDOW_MS), not sample count, so it spans a fixed span of time
         *  regardless of the (variable) fresh-sample rate. */
        private val idToTime = mutableMapOf<Int, Long>()
        private val windowIds = ArrayDeque<Int>()

        /** Previous smoothed value and time for crossing interpolation. */
        private var prevSmoothed = Float.NaN
        private var prevTimeMs = 0L

        /** Schmitt-trigger state: is the signal currently above the median?
         *  The signal must clear the median by a hysteresis band before this flips,
         *  so noise wiggles near the median can't fire extra (double) crossings. */
        private var stateAbove = false
        private var stateInit = false

        /** Raw (no-hysteresis) median-crossing tracking, kept SEPARATE from the
         *  Schmitt state so the catch is TIMED at the true median crossing (where two
         *  consecutive samples straddle the median → interpolation, never extrapolation)
         *  while the Schmitt flip only decides WHETHER to emit. */
        private var prevAboveRaw = false
        private var lastRawCrossMs = 0L
        private var lastRawCrossUp = false
        private var haveRawCross = false

        /** Small ring buffer for smoothing (3-sample moving average). */
        private val smoothBuf = ArrayDeque<Float>(4)

        /** Last detected catch time (ms) for this sensor. */
        var lastCatchMs: Long = 0L
            private set

        /** Second-to-last catch time for stroke period computation. */
        var prevCatchMs: Long = 0L
            private set

        var catchCount: Int = 0
            private set

        /** Current stroke period in ms, derived from last two catches. */
        val strokePeriodMs: Long
            get() = if (prevCatchMs > 0 && lastCatchMs > prevCatchMs)
                lastCatchMs - prevCatchMs else 0L

        // ── Zero-order-hold (degraded acquisition) tracking ──
        // A tick with no fresh BLE sample repeats the last value (the
        // IntervalRecordingService polling loop re-writes latestSample). We measure
        // how long fresh data has been missing; past a fraction of a stroke the
        // seat is DEGRADED_SIGNAL and its waveform there is unmeasurable.
        private var lastFreshTimeMs = 0L

        /** ms since the last fresh (non-held) sample; 0 while fresh. */
        var heldRunMs: Long = 0L
            private set

        /** True when fresh data has been missing for too much of a stroke. */
        var degraded: Boolean = false
            private set

        // ── Gyro magnitude for phase determination ──
        // Accumulate over a lookahead window after each crossing, not just
        // the instant value.
        private var gyroAccumUp = 0.0
        private var gyroSamplesUp = 0
        private var gyroAccumDown = 0.0
        private var gyroSamplesDown = 0

        /** Accumulate gyro until this wall-time after a crossing (GYRO_LOOKAHEAD_MS) —
         *  time-based so it captures the same drive window at any fresh-sample rate. */
        private var gyroLookaheadUntilMs = 0L
        private var lastCrossingWasUp = false

        /** Whether the crossing that corresponds to the catch is upward. */
        private var catchIsUpCrossing: Boolean? = null

        /** Continuously updated gyro averages for potential phase re-evaluation. */
        private var recentGyroUp = 0.0
        private var recentGyroDown = 0.0

        /**
         * Feed a new sample. Call this at the AHRS update rate (~10 Hz).
         *
         * @return the interpolated catch time in ms if a catch was just detected, else null.
         */
        fun onSample(
            timeMs: Long, fresh: Boolean, pitch: Float, roll: Float, yaw: Float,
            wx: Float, wy: Float, wz: Float
        ): Long? {
            // ── Held-sample gate ──
            // No fresh BLE data this tick: the value is a zero-order-hold repeat.
            // Track how long we've been held and skip detection entirely, so a flat
            // plateau neither corrupts the running median nor fabricates a crossing.
            if (!fresh) {
                if (lastFreshTimeMs > 0L) heldRunMs = timeMs - lastFreshTimeMs
                val thresh = max(HELD_FLOOR_MS, (strokePeriodMs * HELD_RUN_FRAC).toLong())
                degraded = heldRunMs > thresh
                return null
            }
            lastFreshTimeMs = timeMs
            heldRunMs = 0L
            degraded = false

            val gyroMag = sqrt(wx * wx + wy * wy + wz * wz)

            // ── Accumulate gyro for GYRO_LOOKAHEAD_MS after the previous crossing ──
            if (timeMs <= gyroLookaheadUntilMs) {
                if (lastCrossingWasUp) {
                    gyroAccumUp += gyroMag
                    gyroSamplesUp++
                } else {
                    gyroAccumDown += gyroMag
                    gyroSamplesDown++
                }
            }

            // ── Phase 1: Channel calibration ──
            updateCalibration(pitch, roll, yaw)

            if (selectedChannel == null) {
                if (calibSamples >= MIN_CALIB_SAMPLES) {
                    selectedChannel = pickChannel()
                }
                return null
            }

            // ── Continuous recalibration check ──
            checkRecalibration()

            // ── Phase 2: Median-crossing catch detection ──
            val value = when (selectedChannel!!) {
                Channel.PITCH -> pitch
                Channel.ROLL -> roll
                Channel.YAW -> yaw
            }

            // Smooth first, then add to the window — both median and crossing
            // operate in the same (smoothed) signal domain.
            smoothBuf.addLast(value)
            if (smoothBuf.size > 3) smoothBuf.removeFirst()
            val smoothed = smoothBuf.average().toFloat()

            // Update signal window with smoothed value
            val id = insertCounter++
            idToValue[id] = smoothed
            idToTime[id] = timeMs
            windowIds.addLast(id)
            insertSortedById(sortedIndices, idToValue, id)

            // Evict by AGE (fixed time span, rate-independent), with a hard sample cap
            // so a burst can't grow the window without bound.
            while (windowIds.isNotEmpty() &&
                (timeMs - idToTime[windowIds.first()]!! > WINDOW_MS ||
                    windowIds.size > MAX_WINDOW_SAMPLES)
            ) {
                val oldestId = windowIds.removeFirst()
                removeSortedById(sortedIndices, idToValue, oldestId)
                idToValue.remove(oldestId)
                idToTime.remove(oldestId)
            }

            if (sortedIndices.size < MIN_WINDOW_FOR_MEDIAN) return null

            // Proper median: average of two middle elements for even-sized windows
            val n = sortedIndices.size
            val median = if (n % 2 == 1) {
                idToValue[sortedIndices[n / 2]]!!
            } else {
                val a = idToValue[sortedIndices[n / 2 - 1]]!!
                val b = idToValue[sortedIndices[n / 2]]!!
                (a + b) / 2f
            }

            if (prevSmoothed.isNaN() || prevTimeMs == 0L || !stateInit) {
                stateAbove = smoothed > median
                prevAboveRaw = smoothed > median
                stateInit = true
                prevSmoothed = smoothed
                prevTimeMs = timeMs
                return null
            }

            // (1) RAW median crossing → the accurate catch TIME. prev and current
            //     straddle the median here, so frac ∈ [0,1] and we interpolate (a
            //     +hysteresis flip point would force an extrapolation and bias the
            //     time ~1 sample late, which does NOT cancel between seats of unequal
            //     amplitude — exactly the bow-vs-stroke case).
            val aboveRaw = smoothed > median
            if (aboveRaw != prevAboveRaw) {
                val d = smoothed - prevSmoothed
                if (abs(d) > 1e-6f) {
                    val frac = ((median - prevSmoothed) / d).coerceIn(0f, 1f)
                    lastRawCrossMs = prevTimeMs + (frac * (timeMs - prevTimeMs)).toLong()
                    lastRawCrossUp = aboveRaw
                    haveRawCross = true
                }
            }
            prevAboveRaw = aboveRaw

            // (2) Schmitt confirmation → decides WHETHER a real swing happened. The
            //     dead-band (±h, scaled to the stroke swing via IQR) rejects the
            //     double-counts a bare crossing makes when a noisy signal grazes the
            //     median more than once per stroke.
            val q1 = idToValue[sortedIndices[n / 4]] ?: median
            val q3 = idToValue[sortedIndices[(3 * n) / 4]] ?: median
            val h = HYST_FRAC * (q3 - q1)
            val newAbove = when {
                smoothed > median + h -> true
                smoothed < median - h -> false
                else -> stateAbove          // inside the dead-band → hold state
            }
            val confirmedFlip = newAbove != stateAbove
            stateAbove = newAbove

            prevSmoothed = smoothed
            prevTimeMs = timeMs

            // Emit only on a confirmed swing that has a matching raw crossing to time it.
            if (!confirmedFlip || !haveRawCross || lastRawCrossUp != newAbove) return null
            val crossingUp = newAbove
            val crossingTimeMs = lastRawCrossMs   // the TRUE median-crossing time
            haveRawCross = false

            // Start the (time-based) gyro lookahead for this crossing.
            gyroLookaheadUntilMs = timeMs + GYRO_LOOKAHEAD_MS
            lastCrossingWasUp = crossingUp

            // Determine which direction is the catch (higher gyro after = drive phase)
            if (catchIsUpCrossing == null
                && gyroSamplesUp >= MIN_GYRO_SAMPLES
                && gyroSamplesDown >= MIN_GYRO_SAMPLES
            ) {
                recentGyroUp = gyroAccumUp / gyroSamplesUp
                recentGyroDown = gyroAccumDown / gyroSamplesDown
                catchIsUpCrossing = recentGyroUp > recentGyroDown
            }

            // If we haven't determined phase yet, don't report catches
            if (catchIsUpCrossing == null) return null

            // Only report if this crossing matches the catch direction
            val isCatchDirection = if (catchIsUpCrossing == true) crossingUp else !crossingUp
            if (!isCatchDirection) return null

            // Refractory period: min gap between catches = a fraction of the stroke
            // period (mirrors the portal's refractory_frac), floored while no period
            // is known yet. Backstop to the Schmitt trigger against double-counts.
            val debounceMs = if (strokePeriodMs > 0) {
                max(DEBOUNCE_FLOOR_MS, (strokePeriodMs * REFRACTORY_FRAC).toLong())
            } else {
                DEBOUNCE_FLOOR_MS
            }
            if (lastCatchMs > 0 && crossingTimeMs - lastCatchMs < debounceMs) return null

            prevCatchMs = lastCatchMs
            lastCatchMs = crossingTimeMs
            catchCount++
            return crossingTimeMs
        }

        fun reset() {
            calibSamples = 0
            pitchMean = 0.0; pitchM2 = 0.0
            rollMean = 0.0; rollM2 = 0.0
            yawMean = 0.0; yawM2 = 0.0
            selectedChannel = null
            emaVariancePitch = 0.0; emaVarianceRoll = 0.0; emaVarianceYaw = 0.0
            recalibCounter = 0
            sortedIndices.clear()
            idToValue.clear()
            idToTime.clear()
            windowIds.clear()
            insertCounter = 0
            smoothBuf.clear()
            prevSmoothed = Float.NaN
            prevTimeMs = 0L
            stateAbove = false
            stateInit = false
            prevAboveRaw = false
            lastRawCrossMs = 0L
            lastRawCrossUp = false
            haveRawCross = false
            lastCatchMs = 0L
            prevCatchMs = 0L
            catchCount = 0
            gyroAccumUp = 0.0; gyroSamplesUp = 0
            gyroAccumDown = 0.0; gyroSamplesDown = 0
            gyroLookaheadUntilMs = 0L
            catchIsUpCrossing = null
            recentGyroUp = 0.0; recentGyroDown = 0.0
            lastFreshTimeMs = 0L
            heldRunMs = 0L
            degraded = false
        }

        // ── Welford's online variance ──

        private fun updateCalibration(pitch: Float, roll: Float, yaw: Float) {
            calibSamples++
            val n = calibSamples.toDouble()

            val dP = pitch - pitchMean; pitchMean += dP / n; pitchM2 += dP * (pitch - pitchMean)
            val dR = roll - rollMean;   rollMean += dR / n;  rollM2 += dR * (roll - rollMean)
            val dY = yaw - yawMean;     yawMean += dY / n;   yawM2 += dY * (yaw - yawMean)

            // Update EMAs for continuous recalibration (after initial selection)
            if (selectedChannel != null && calibSamples > MIN_CALIB_SAMPLES) {
                val alpha = EMA_ALPHA
                val instVarP = dP * (pitch - pitchMean)
                val instVarR = dR * (roll - rollMean)
                val instVarY = dY * (yaw - yawMean)
                emaVariancePitch = emaVariancePitch * (1 - alpha) + abs(instVarP) * alpha
                emaVarianceRoll = emaVarianceRoll * (1 - alpha) + abs(instVarR) * alpha
                emaVarianceYaw = emaVarianceYaw * (1 - alpha) + abs(instVarY) * alpha
            }
        }

        private fun pickChannel(): Channel {
            if (calibSamples < 2) return Channel.PITCH
            val vp = pitchM2 / (calibSamples - 1)
            val vr = rollM2 / (calibSamples - 1)
            val vy = yawM2 / (calibSamples - 1)

            // Initialize EMAs
            emaVariancePitch = vp
            emaVarianceRoll = vr
            emaVarianceYaw = vy

            return when {
                vp >= vr && vp >= vy -> Channel.PITCH
                vr >= vp && vr >= vy -> Channel.ROLL
                else -> Channel.YAW
            }
        }

        private fun checkRecalibration() {
            if (selectedChannel == null) return
            val currentEma = when (selectedChannel!!) {
                Channel.PITCH -> emaVariancePitch
                Channel.ROLL -> emaVarianceRoll
                Channel.YAW -> emaVarianceYaw
            }
            if (currentEma < 1e-6) return

            // Find the channel with max EMA variance
            val maxEma = maxOf(emaVariancePitch, emaVarianceRoll, emaVarianceYaw)
            if (maxEma > currentEma * RECALIB_RATIO) {
                recalibCounter++
                if (recalibCounter >= RECALIB_SAMPLES) {
                    // Switch channel, reset crossing state but keep calibration running
                    selectedChannel = when {
                        emaVariancePitch >= emaVarianceRoll && emaVariancePitch >= emaVarianceYaw -> Channel.PITCH
                        emaVarianceRoll >= emaVariancePitch && emaVarianceRoll >= emaVarianceYaw -> Channel.ROLL
                        else -> Channel.YAW
                    }
                    recalibCounter = 0
                    // Reset crossing detection state for the new channel
                    sortedIndices.clear()
                    idToValue.clear()
                    idToTime.clear()
                    windowIds.clear()
                    smoothBuf.clear()
                    prevSmoothed = Float.NaN
                    stateInit = false      // re-arm the Schmitt trigger for the new channel
                    haveRawCross = false
                    // Keep catch times and gyro phase — those are still valid
                }
            } else {
                recalibCounter = 0
            }
        }

        companion object {
            // Time-based windows (rate-independent — the fresh-sample rate varies with
            // the BLE link, so sample-count windows would silently change duration).
            private const val WINDOW_MS = 20_000L      // running-median span (~several strokes)
            private const val MAX_WINDOW_SAMPLES = 4000 // hard cap so a burst can't grow it unbounded
            private const val GYRO_LOOKAHEAD_MS = 300L  // accumulate drive-phase gyro this long after a crossing
            private const val MIN_GYRO_SAMPLES = 9      // gyro samples per direction before fixing catch phase
            private const val MIN_WINDOW_FOR_MEDIAN = 10
            private const val MIN_CALIB_SAMPLES = 30
            private const val EMA_ALPHA = 0.02     // ~50-sample half-life
            private const val RECALIB_RATIO = 2.0  // other channel must be 2× current
            private const val RECALIB_SAMPLES = 50 // must dominate for 50 consecutive samples (~5s)

            // Degraded-acquisition gate (mirrors portal max_held_run_frac = 0.30).
            private const val HELD_RUN_FRAC = 0.30 // held > this fraction of a stroke → degraded
            private const val HELD_FLOOR_MS = 500L // ...but at least this, before a period is known

            // Schmitt hysteresis band as a fraction of the window IQR (~a quarter of
            // the stroke swing) — kills double-counts from a signal grazing the median.
            private const val HYST_FRAC = 0.20f
            // Refractory period as a fraction of the stroke (mirrors portal refractory_frac).
            private const val REFRACTORY_FRAC = 0.55
        }
    }

    // ── Public API ──

    private val calibrators = mutableMapOf<String, SensorCalibrator>()

    /** Latest per-sensor lateness relative to the reference (stroke) sensor, in ms. */
    private val _lateness = mutableMapOf<String, Long>()

    /** When each sensor's lateness was last (re)computed, for staleness. */
    private val _latenessUpdatedMs = mutableMapOf<String, Long>()

    /** Most recent sample time seen across all sensors (the "now" for staleness). */
    private var lastSampleTimeMs = 0L

    /** Reference sensor MAC (stroke = highest seat index). */
    private var referenceMac: String? = null

    /**
     * Register a sensor. Call once per sensor when the interval starts. Order does
     * not matter — the reference is chosen by seat number (highest = stroke).
     */
    @Synchronized
    fun addSensor(mac: String, seatIndex: Int) {
        calibrators[mac] = SensorCalibrator(mac, seatIndex)
        // Reference = the STROKE seat = HIGHEST seat number. In rowing, bow is seat 1
        // and stroke (highest number) sets the rhythm; everyone synchronises to
        // stroke, so stroke reads 0 lateness. seatIndex here equals the user-facing
        // "Seat N" number (same formula in SensorLabelBuilder), and this matches the
        // portal, which also uses the highest seat index as the reference.
        if (referenceMac == null || seatIndex > (calibrators[referenceMac]?.seatIndex ?: Int.MIN_VALUE)) {
            referenceMac = mac
        }
    }

    /**
     * Feed a new sample from a sensor. Call this every time the AHRS updates (~10 Hz).
     *
     * @return the lateness in ms for this sensor vs the reference, or null if
     *         no catch was detected or lateness can't be computed yet.
     */
    @Synchronized
    fun onSample(
        mac: String, timeMs: Long, fresh: Boolean,
        pitch: Float, roll: Float, yaw: Float,
        wx: Float, wy: Float, wz: Float
    ): Long? {
        if (timeMs > lastSampleTimeMs) lastSampleTimeMs = timeMs
        val cal = calibrators[mac] ?: return null
        val catchTimeMs = cal.onSample(timeMs, fresh, pitch, roll, yaw, wx, wy, wz)
        // Drop a degraded seat's stale lateness from analyzer state too (not just the
        // display), so getLateness() can never hand back a frozen value.
        if (cal.degraded) {
            _lateness.remove(mac)
            _latenessUpdatedMs.remove(mac)
        }
        if (catchTimeMs == null) return null

        val refMac = referenceMac ?: return null
        val refCal = calibrators[refMac] ?: return null

        // Compute the pairing window from the reference sensor's stroke period.
        // Clamp to [PAIR_FLOOR_MS, PAIR_CEILING_MS].
        val refPeriod = refCal.strokePeriodMs
        val pairWindow = if (refPeriod > 0) {
            clampLong(PAIR_FLOOR_MS, (refPeriod * PAIR_FRACTION).toLong(), PAIR_CEILING_MS)
        } else {
            PAIR_CEILING_MS // no stroke period yet — use ceiling
        }

        if (mac == refMac) {
            // Reference just caught — check other sensors. A degraded seat is skipped
            // (its catch is stale), so it never pairs off a held plateau.
            for ((otherMac, otherCal) in calibrators) {
                if (otherMac == refMac) continue
                if (otherCal.lastCatchMs == 0L || otherCal.degraded) continue
                val dt = otherCal.lastCatchMs - catchTimeMs
                if (abs(dt) < pairWindow) {
                    _lateness[otherMac] = dt
                    _latenessUpdatedMs[otherMac] = timeMs
                }
            }
            _lateness[refMac] = 0L
            _latenessUpdatedMs[refMac] = timeMs
            return 0L
        } else {
            // Non-reference caught — check against reference
            if (refCal.lastCatchMs == 0L) return null
            val dt = catchTimeMs - refCal.lastCatchMs
            if (abs(dt) < pairWindow) {
                _lateness[mac] = dt
                _latenessUpdatedMs[mac] = timeMs
                return dt
            }
            return null
        }
    }

    /** Get the latest lateness in ms for a sensor. Null if not yet computed. */
    @Synchronized
    fun getLateness(mac: String): Long? = _lateness[mac]

    /**
     * Per-seat real-time quality (shared spec, RESEARCH.md §8.5). One bad seat is
     * flagged individually — it never affects any other seat's status:
     *   CALIBRATING     — axis not locked yet
     *   DEGRADED_SIGNAL — sensor spaced out (held samples); lateness unmeasurable
     *   STALE           — calibrated but no recent paired catch vs the stroke
     *   OK              — fresh data + a recent paired catch
     */
    @Synchronized
    fun getStatus(mac: String): SensorSyncStatus {
        val cal = calibrators[mac] ?: return SensorSyncStatus.CALIBRATING
        if (cal.selectedChannel == null) return SensorSyncStatus.CALIBRATING
        if (cal.degraded) return SensorSyncStatus.DEGRADED_SIGNAL
        val updated = _latenessUpdatedMs[mac] ?: 0L
        return if (updated > 0L && lastSampleTimeMs - updated <= STALE_WINDOW_MS)
            SensorSyncStatus.OK else SensorSyncStatus.STALE
    }

    /** Get the selected channel name for a sensor, or null if still calibrating. */
    @Synchronized
    fun getChannelName(mac: String): String? =
        calibrators[mac]?.selectedChannel?.name?.lowercase()

    /** Is the given sensor calibrated and detecting catches? */
    @Synchronized
    fun isCalibrated(mac: String): Boolean =
        calibrators[mac]?.selectedChannel != null

    /** Reset all state (e.g. when starting a new interval). */
    @Synchronized
    fun reset() {
        calibrators.values.forEach { it.reset() }
        _lateness.clear()
        _latenessUpdatedMs.clear()
        lastSampleTimeMs = 0L
        referenceMac = null
    }

    /** Remove all sensors. */
    @Synchronized
    fun clear() {
        calibrators.clear()
        _lateness.clear()
        _latenessUpdatedMs.clear()
        lastSampleTimeMs = 0L
        referenceMac = null
    }

    // ── Sorted-by-id helpers for running median (avoids Float equality) ──

    companion object {
        /** Adaptive debounce floor — allows up to ~75 SPM. */
        private const val DEBOUNCE_FLOOR_MS = 800L

        /** Pairing window as fraction of stroke period. */
        private const val PAIR_FRACTION = 0.45

        /** Minimum pairing window — catches >500ms apart are suspect. */
        private const val PAIR_FLOOR_MS = 500L

        /** Maximum pairing window — even at very low SPM, cap it. */
        private const val PAIR_CEILING_MS = 1200L

        /** No paired catch within this long → seat is STALE (shows "--", not a frozen value). */
        private const val STALE_WINDOW_MS = 6000L

        private fun clampLong(lo: Long, value: Long, hi: Long): Long =
            max(lo, min(value, hi))

        /**
         * Insert [id] into [indices] sorted by the corresponding value in [values].
         * Uses binary search. O(log n) search + O(n) shift — acceptable for n ≤ 200.
         */
        private fun insertSortedById(
            indices: MutableList<Int>, values: Map<Int, Float>, id: Int
        ) {
            val v = values[id] ?: return
            var lo = 0; var hi = indices.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val midVal = values[indices[mid]] ?: 0f
                if (midVal < v) lo = mid + 1 else hi = mid
            }
            indices.add(lo, id)
        }

        /**
         * Remove [id] from [indices]. Looks up by id (unique integer), not by float
         * value, so there is no Float equality problem.
         */
        private fun removeSortedById(
            indices: MutableList<Int>, values: Map<Int, Float>, id: Int
        ) {
            // Binary search by value to narrow the range, then linear scan for exact id.
            val v = values[id] ?: return
            var lo = 0; var hi = indices.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val midVal = values[indices[mid]] ?: 0f
                if (midVal < v) lo = mid + 1 else hi = mid
            }
            // Scan from lo for the exact id (handles duplicate values)
            for (i in lo until indices.size) {
                if (indices[i] == id) {
                    indices.removeAt(i)
                    return
                }
                // Past the value range — stop
                val iv = values[indices[i]] ?: 0f
                if (iv > v + 1e-4f) return
            }
        }
    }
}
