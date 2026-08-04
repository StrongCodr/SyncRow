package com.strongcodr.syncrow.model

/**
 * Per-seat real-time sync quality state.
 *
 * SHARED SPEC with the portal's `crosssensor.seat_status` (SyncRow_Portal
 * RESEARCH.md §8.5). The rule both tiers must honour: **one bad seat never blanks
 * the crew.** A seat that spaces out or stops matching is marked individually and
 * shown as unavailable; every other seat keeps updating on its own strokes.
 *
 * Acquisition is judged before rowing, so "the sensor spaced out" (DEGRADED_SIGNAL)
 * is never mislabelled as a rowing problem.
 */
enum class SensorSyncStatus {
    /** Warming up — the sensor's stroke axis/channel isn't locked yet. */
    CALIBRATING,

    /** Fresh data and a recent paired catch — the lateness is trustworthy. */
    OK,

    /**
     * The sensor spaced out: BLE stalled and the sampling loop is re-writing the
     * last value (zero-order-hold). Portal analogue: `degraded_signal`. The
     * lateness is not measurable, so we show "--" rather than a frozen number.
     */
    DEGRADED_SIGNAL,

    /** Calibrated and delivering data, but no recent paired catch vs the stroke. */
    STALE
}
