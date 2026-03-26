package com.strongcodr.syncrow

import kotlin.math.abs
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
 */
class StrokeAnalyzer {

    /** Per-sensor state for calibration and catch detection. */
    private class SensorCalibrator(val mac: String, val seatIndex: Int) {

        // ── Channel selection (auto-calibration) ──

        enum class Channel { PITCH, ROLL, YAW }

        /** Running variance accumulators per channel for the first N samples. */
        private var pitchSum = 0.0; private var pitchSqSum = 0.0
        private var rollSum = 0.0;  private var rollSqSum = 0.0
        private var yawSum = 0.0;   private var yawSqSum = 0.0
        private var calibSamples = 0

        var selectedChannel: Channel? = null
            private set

        /** Minimum samples before we pick a channel (~3 seconds at 10 Hz). */
        private val minCalibSamples = 30

        // ── Median-crossing state ──

        /** Sliding window of recent signal values for computing running median. */
        private val signalWindow = ArrayDeque<Float>(WINDOW_SIZE + 1)

        /** Running sorted copy for O(1) median lookup. */
        private val sortedWindow = mutableListOf<Float>()

        /** Previous smoothed value for crossing detection. */
        private var prevSmoothed = Float.NaN
        private var prevTimeMs = 0L

        /** Small ring buffer for smoothing (100ms at ~10 Hz = ~1 sample, but we keep 3). */
        private val smoothBuf = ArrayDeque<Float>(4)

        /** Last detected catch time (ms) for this sensor. */
        var lastCatchMs: Long = 0L
            private set

        /** Second-to-last catch time for SPM computation. */
        var prevCatchMs: Long = 0L
            private set

        var catchCount: Int = 0
            private set

        // ── Gyro magnitude for phase determination ──
        private var gyroAfterCrossUp = 0.0
        private var gyroAfterCrossDown = 0.0
        private var crossUpCount = 0
        private var crossDownCount = 0

        /** Whether the crossing that corresponds to the catch is upward. */
        private var catchIsUpCrossing: Boolean? = null

        /** Track previous above/below state for crossing detection. */
        private var wasAboveMedian: Boolean? = null

        /**
         * Feed a new sample. Call this at the AHRS update rate (~10 Hz).
         *
         * @return the interpolated catch time in ms if a catch was just detected, else null.
         */
        fun onSample(
            timeMs: Long, pitch: Float, roll: Float, yaw: Float,
            wx: Float, wy: Float, wz: Float
        ): Long? {
            // ── Phase 1: Channel calibration ──
            if (selectedChannel == null) {
                pitchSum += pitch; pitchSqSum += pitch * pitch
                rollSum += roll;   rollSqSum += roll * roll
                yawSum += yaw;     yawSqSum += yaw * yaw
                calibSamples++

                if (calibSamples >= minCalibSamples) {
                    selectedChannel = pickChannel()
                }
                return null
            }

            // ── Phase 2: Median-crossing catch detection ──
            val value = when (selectedChannel!!) {
                Channel.PITCH -> pitch
                Channel.ROLL -> roll
                Channel.YAW -> yaw
            }

            // Update signal window
            signalWindow.addLast(value)
            insertSorted(sortedWindow, value)
            if (signalWindow.size > WINDOW_SIZE) {
                val removed = signalWindow.removeFirst()
                removeSorted(sortedWindow, removed)
            }

            if (sortedWindow.size < MIN_WINDOW_FOR_MEDIAN) return null

            val median = sortedWindow[sortedWindow.size / 2]

            // Light smoothing (3-sample moving average)
            smoothBuf.addLast(value)
            if (smoothBuf.size > 3) smoothBuf.removeFirst()
            val smoothed = smoothBuf.average().toFloat()

            val gyroMag = sqrt(wx * wx + wy * wy + wz * wz)

            val aboveMedian = smoothed > median

            if (prevSmoothed.isNaN() || prevTimeMs == 0L) {
                prevSmoothed = smoothed
                prevTimeMs = timeMs
                wasAboveMedian = aboveMedian
                return null
            }

            val prevAbove = wasAboveMedian
            wasAboveMedian = aboveMedian

            // No crossing
            if (prevAbove == null || aboveMedian == prevAbove) {
                // Still accumulate gyro for phase determination
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

            // Track gyro magnitude per crossing direction for phase detection
            if (aboveMedian) {
                // Upward crossing
                gyroAfterCrossUp += gyroMag
                crossUpCount++
            } else {
                // Downward crossing
                gyroAfterCrossDown += gyroMag
                crossDownCount++
            }

            prevSmoothed = smoothed
            prevTimeMs = timeMs

            // Determine which direction is the catch (higher gyro = drive phase)
            if (catchIsUpCrossing == null && crossUpCount >= 2 && crossDownCount >= 2) {
                val avgUp = gyroAfterCrossUp / crossUpCount
                val avgDown = gyroAfterCrossDown / crossDownCount
                catchIsUpCrossing = avgUp > avgDown
            }

            // If we haven't determined phase yet, don't report catches
            if (catchIsUpCrossing == null) return null

            // Only report if this crossing matches the catch direction
            val isCatchDirection = if (catchIsUpCrossing == true) aboveMedian else !aboveMedian
            if (!isCatchDirection) return null

            // Debounce: minimum 1 second between catches (max ~60 SPM)
            if (lastCatchMs > 0 && crossingTimeMs - lastCatchMs < 1000) return null

            prevCatchMs = lastCatchMs
            lastCatchMs = crossingTimeMs
            catchCount++
            return crossingTimeMs
        }

        fun reset() {
            pitchSum = 0.0; pitchSqSum = 0.0
            rollSum = 0.0; rollSqSum = 0.0
            yawSum = 0.0; yawSqSum = 0.0
            calibSamples = 0
            selectedChannel = null
            signalWindow.clear()
            sortedWindow.clear()
            smoothBuf.clear()
            prevSmoothed = Float.NaN
            prevTimeMs = 0L
            wasAboveMedian = null
            lastCatchMs = 0L
            prevCatchMs = 0L
            catchCount = 0
            gyroAfterCrossUp = 0.0
            gyroAfterCrossDown = 0.0
            crossUpCount = 0
            crossDownCount = 0
            catchIsUpCrossing = null
        }

        private fun pickChannel(): Channel {
            fun variance(sum: Double, sqSum: Double, n: Int): Double {
                if (n < 2) return 0.0
                val mean = sum / n
                return (sqSum / n) - mean * mean
            }
            val vp = variance(pitchSum, pitchSqSum, calibSamples)
            val vr = variance(rollSum, rollSqSum, calibSamples)
            val vy = variance(yawSum, yawSqSum, calibSamples)
            return when {
                vp >= vr && vp >= vy -> Channel.PITCH
                vr >= vp && vr >= vy -> Channel.ROLL
                else -> Channel.YAW
            }
        }

        companion object {
            /** How many recent signal samples to keep for the running median. */
            private const val WINDOW_SIZE = 200  // ~20 seconds at 10 Hz

            /** Minimum samples in window before we start computing median. */
            private const val MIN_WINDOW_FOR_MEDIAN = 10
        }
    }

    // ── Public API ──

    private val calibrators = mutableMapOf<String, SensorCalibrator>() // key: mac

    /** Latest per-sensor lateness relative to the reference (stroke) sensor, in ms. */
    private val _lateness = mutableMapOf<String, Long>() // key: mac, value: Δt in ms

    /** Reference sensor MAC (lowest seat index). */
    private var referenceMac: String? = null

    /**
     * Register a sensor. Call once per sensor when the interval starts.
     * Sensors should be added in seat order (stroke first).
     */
    fun addSensor(mac: String, seatIndex: Int) {
        calibrators[mac] = SensorCalibrator(mac, seatIndex)
        // Reference = lowest seat index (stroke rower)
        if (referenceMac == null || seatIndex < (calibrators[referenceMac]?.seatIndex ?: Int.MAX_VALUE)) {
            referenceMac = mac
        }
    }

    /**
     * Feed a new sample from a sensor. Call this every time the AHRS updates (~10 Hz).
     *
     * @return the lateness in ms for this sensor vs the reference, or null if
     *         no catch was detected on this sample or lateness can't be computed yet.
     */
    fun onSample(
        mac: String, timeMs: Long,
        pitch: Float, roll: Float, yaw: Float,
        wx: Float, wy: Float, wz: Float
    ): Long? {
        val cal = calibrators[mac] ?: return null
        val catchTimeMs = cal.onSample(timeMs, pitch, roll, yaw, wx, wy, wz) ?: return null

        // If this is the reference sensor, compute lateness for all others
        // that had a recent catch (within 1 stroke period).
        val refMac = referenceMac ?: return null
        val refCal = calibrators[refMac] ?: return null

        if (mac == refMac) {
            // Reference just caught — check if any other sensor caught within tolerance
            for ((otherMac, otherCal) in calibrators) {
                if (otherMac == refMac) continue
                if (otherCal.lastCatchMs == 0L) continue
                val dt = otherCal.lastCatchMs - catchTimeMs
                if (abs(dt) < MAX_CATCH_PAIR_WINDOW_MS) {
                    _lateness[otherMac] = dt
                }
            }
            _lateness[refMac] = 0L
            return 0L
        } else {
            // Non-reference caught — check against reference's last catch
            if (refCal.lastCatchMs == 0L) return null
            val dt = catchTimeMs - refCal.lastCatchMs
            if (abs(dt) < MAX_CATCH_PAIR_WINDOW_MS) {
                _lateness[mac] = dt
                return dt
            }
            return null
        }
    }

    /** Get the latest lateness in ms for a sensor. Null if not yet computed. */
    fun getLateness(mac: String): Long? = _lateness[mac]

    /** Get the selected channel name for a sensor, or null if still calibrating. */
    fun getChannelName(mac: String): String? =
        calibrators[mac]?.selectedChannel?.name?.lowercase()

    /** Is the given sensor calibrated and detecting catches? */
    fun isCalibrated(mac: String): Boolean =
        calibrators[mac]?.selectedChannel != null

    /** Reset all state (e.g. when starting a new interval). */
    fun reset() {
        calibrators.values.forEach { it.reset() }
        _lateness.clear()
        referenceMac = null
    }

    /** Remove all sensors. */
    fun clear() {
        calibrators.clear()
        _lateness.clear()
        referenceMac = null
    }

    // ── Sorted list helpers for O(n) running median ──

    companion object {
        /** Max time between a reference catch and another sensor's catch to be paired. */
        private const val MAX_CATCH_PAIR_WINDOW_MS = 1500L

        private fun insertSorted(list: MutableList<Float>, value: Float) {
            var lo = 0; var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid] < value) lo = mid + 1 else hi = mid
            }
            list.add(lo, value)
        }

        private fun removeSorted(list: MutableList<Float>, value: Float) {
            var lo = 0; var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid] < value) lo = mid + 1 else hi = mid
            }
            if (lo < list.size && list[lo] == value) list.removeAt(lo)
        }
    }
}
