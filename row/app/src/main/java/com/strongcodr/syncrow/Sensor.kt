package com.strongcodr.syncrow

/** Visual marker prefix for cox/hull sensor rows. Single source so we can change or
 *  remove the indicator without hunting through fragments. */
const val COX_INDICATOR = "⚓"

enum class SensorRole {
    /** A rowing seat. Participates in stroke detection, numbered 1..N. */
    SEAT,
    /** A hull-mounted IMU, conceptually the coxswain's position. Labeled "Cox" and
     *  exempt from stroke detection. At most one sensor in the list may be COX. */
    COX
}

data class Sensor(
    val id: Long,
    val mac: String,
    val name: String?,
    val role: SensorRole = SensorRole.SEAT
)
