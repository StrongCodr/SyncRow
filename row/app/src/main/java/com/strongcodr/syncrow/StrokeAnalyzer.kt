package com.strongcodr.syncrow

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
 * the analyzer computes Δt (lateness) relative to the reference sensor
 * (seat with the lowest seat index, i.e. the stroke rower).
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

        /** Sliding window of recent *smoothed* signal values for the running median. */
        private val signalWindow = ArrayDeque<Float>(WINDOW_SIZE + 1)

        /** Running sorted copy for median lookup.  We use integer indices
         *  into [signalWindow] rather than storing floats directly, to avoid
         *  the Float equality problem on removal. */
        private val sortedIndices = mutableListOf<Int>()

        /** Monotonically increasing insert counter — each sample gets a unique id. */
        private var insertCounter = 0

        /** Map from insert-counter id to the smoothed value, for sorting. */
        private val idToValue = mutableMapOf<Int, Float>()

        /** Previous smoothed value and time for crossing interpolation. */
        private var prevSmoothed = Float.NaN
        private var prevTimeMs = 0L

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

        // ── Gyro magnitude for phase determination ──
        // Accumulate over a lookahead window after each crossing, not just
        // the instant value.
        private var gyroAccumUp = 0.0
        private var gyroSamplesUp = 0
        private var gyroAccumDown = 0.0
        private var gyroSamplesDown = 0

        /** How many samples to accumulate gyro after a crossing (~300ms at 10 Hz). */
        private var gyroLookaheadRemaining = 0
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
            timeMs: Long, pitch: Float, roll: Float, yaw: Float,
            wx: Float, wy: Float, wz: Float
        ): Long? {
            val gyroMag = sqrt(wx * wx + wy * wy + wz * wz)

            // ── Accumulate gyro lookahead from previous crossing ──
            if (gyroLookaheadRemaining > 0) {
                if (lastCrossingWasUp) {
                    gyroAccumUp += gyroMag
                    gyroSamplesUp++
                } else {
                    gyroAccumDown += gyroMag
                    gyroSamplesDown++
                }
                gyroLookaheadRemaining--
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
            signalWindow.addLast(smoothed)
            idToValue[id] = smoothed
            insertSortedById(sortedIndices, idToValue, id)

            if (signalWindow.size > WINDOW_SIZE) {
                val oldestId = id - WINDOW_SIZE
                signalWindow.removeFirst()
                removeSortedById(sortedIndices, idToValue, oldestId)
                idToValue.remove(oldestId)
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

            val aboveMedian = smoothed > median

            if (prevSmoothed.isNaN() || prevTimeMs == 0L) {
                prevSmoothed = smoothed
                prevTimeMs = timeMs
                return null
            }

            // Check for crossing
            val prevAbove = prevSmoothed > (median - 0.01f) // small hysteresis
            if (aboveMedian == prevAbove) {
                prevSmoothed = smoothed
                prevTimeMs = timeMs
                return null
            }

            // ── Crossing detected ──
            val denom = smoothed - prevSmoothed
            if (abs(denom) < 1e-6f) {
                prevSmoothed = smoothed
                prevTimeMs = timeMs
                return null
            }

            // Linear interpolation for sub-sample precision
            val frac = (median - prevSmoothed) / denom
            val crossingTimeMs = prevTimeMs + (frac * (timeMs - prevTimeMs)).toLong()

            // Start gyro lookahead accumulation for this crossing
            gyroLookaheadRemaining = GYRO_LOOKAHEAD_SAMPLES
            lastCrossingWasUp = aboveMedian

            prevSmoothed = smoothed
            prevTimeMs = timeMs

            // Determine which direction is the catch (higher gyro after = drive phase)
            if (catchIsUpCrossing == null
                && gyroSamplesUp >= GYRO_LOOKAHEAD_SAMPLES * 3
                && gyroSamplesDown >= GYRO_LOOKAHEAD_SAMPLES * 3
            ) {
                recentGyroUp = gyroAccumUp / gyroSamplesUp
                recentGyroDown = gyroAccumDown / gyroSamplesDown
                catchIsUpCrossing = recentGyroUp > recentGyroDown
            }

            // If we haven't determined phase yet, don't report catches
            if (catchIsUpCrossing == null) return null

            // Only report if this crossing matches the catch direction
            val isCatchDirection = if (catchIsUpCrossing == true) aboveMedian else !aboveMedian
            if (!isCatchDirection) return null

            // Adaptive debounce: max(floor, strokePeriod * 0.4)
            val debounceMs = if (strokePeriodMs > 0) {
                max(DEBOUNCE_FLOOR_MS, (strokePeriodMs * 0.4).toLong())
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
            signalWindow.clear()
            sortedIndices.clear()
            idToValue.clear()
            insertCounter = 0
            smoothBuf.clear()
            prevSmoothed = Float.NaN
            prevTimeMs = 0L
            lastCatchMs = 0L
            prevCatchMs = 0L
            catchCount = 0
            gyroAccumUp = 0.0; gyroSamplesUp = 0
            gyroAccumDown = 0.0; gyroSamplesDown = 0
            gyroLookaheadRemaining = 0
            catchIsUpCrossing = null
            recentGyroUp = 0.0; recentGyroDown = 0.0
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
                    signalWindow.clear()
                    sortedIndices.clear()
                    idToValue.clear()
                    smoothBuf.clear()
                    prevSmoothed = Float.NaN
                    // Keep catch times and gyro phase — those are still valid
                }
            } else {
                recalibCounter = 0
            }
        }

        companion object {
            private const val WINDOW_SIZE = 200   // ~20 seconds at 10 Hz
            private const val MIN_WINDOW_FOR_MEDIAN = 10
            private const val MIN_CALIB_SAMPLES = 30
            private const val EMA_ALPHA = 0.02     // ~50-sample half-life
            private const val RECALIB_RATIO = 2.0  // other channel must be 2× current
            private const val RECALIB_SAMPLES = 50 // must dominate for 50 consecutive samples (~5s)
            private const val GYRO_LOOKAHEAD_SAMPLES = 3  // ~300ms at 10 Hz
        }
    }

    // ── Public API ──

    private val calibrators = mutableMapOf<String, SensorCalibrator>()

    /** Latest per-sensor lateness relative to the reference (stroke) sensor, in ms. */
    private val _lateness = mutableMapOf<String, Long>()

    /** Reference sensor MAC (lowest seat index). */
    private var referenceMac: String? = null

    /**
     * Register a sensor. Call once per sensor when the interval starts.
     * Sensors should be added in seat order (stroke first).
     */
    @Synchronized
    fun addSensor(mac: String, seatIndex: Int) {
        calibrators[mac] = SensorCalibrator(mac, seatIndex)
        if (referenceMac == null || seatIndex < (calibrators[referenceMac]?.seatIndex ?: Int.MAX_VALUE)) {
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
        mac: String, timeMs: Long,
        pitch: Float, roll: Float, yaw: Float,
        wx: Float, wy: Float, wz: Float
    ): Long? {
        val cal = calibrators[mac] ?: return null
        val catchTimeMs = cal.onSample(timeMs, pitch, roll, yaw, wx, wy, wz) ?: return null

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
            // Reference just caught — check other sensors
            for ((otherMac, otherCal) in calibrators) {
                if (otherMac == refMac) continue
                if (otherCal.lastCatchMs == 0L) continue
                val dt = otherCal.lastCatchMs - catchTimeMs
                if (abs(dt) < pairWindow) {
                    _lateness[otherMac] = dt
                }
            }
            _lateness[refMac] = 0L
            return 0L
        } else {
            // Non-reference caught — check against reference
            if (refCal.lastCatchMs == 0L) return null
            val dt = catchTimeMs - refCal.lastCatchMs
            if (abs(dt) < pairWindow) {
                _lateness[mac] = dt
                return dt
            }
            return null
        }
    }

    /** Get the latest lateness in ms for a sensor. Null if not yet computed. */
    @Synchronized
    fun getLateness(mac: String): Long? = _lateness[mac]

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
        referenceMac = null
    }

    /** Remove all sensors. */
    @Synchronized
    fun clear() {
        calibrators.clear()
        _lateness.clear()
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
