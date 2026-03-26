package com.strongcodr.syncrow.model

data class IntervalUpload(
    val interval_id: Long,
    val sensor_id: String,
    val seat: String?,
    val samples: List<SensorSample>
)
