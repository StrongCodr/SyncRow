package com.strongcodr.syncrow.model

data class LocationUpload(
    val interval_id: Long,
    val samples: List<LocationSample>
)
