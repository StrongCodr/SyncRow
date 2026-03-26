package com.strongcodr.syncrow.model

data class SensorSample(
    val timestampMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val wx: Float? = null,
    val wy: Float? = null,
    val wz: Float? = null,
    val roll: Float?,
    val pitch: Float?,
    val yaw: Float?
)
