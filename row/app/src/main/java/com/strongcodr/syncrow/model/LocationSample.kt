package com.strongcodr.syncrow.model

data class LocationSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?
)
