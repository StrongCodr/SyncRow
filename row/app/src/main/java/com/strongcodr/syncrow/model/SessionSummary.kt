package com.strongcodr.syncrow.model

data class SessionSummary(
    val intervalId: Long,
    val durationMs: Long,
    val seats: List<SeatSummary>
)

data class SeatSummary(
    val seatIndex: Int,
    val displayName: String,
    val avgSpm: Int,
    val maxSpm: Int,
    val strokes: Int
)

