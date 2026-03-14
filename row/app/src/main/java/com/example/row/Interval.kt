package com.example.row

data class Interval(
    val id: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val avgSpm: Double?,
    val maxSpm: Double?
)
