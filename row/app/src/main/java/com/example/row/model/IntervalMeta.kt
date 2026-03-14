package com.example.row.model

data class IntervalMeta(
    val id: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val syncStatus: SyncStatus,
    val locationSyncStatus: SyncStatus? = null,
    val lastSensorSyncAt: Long? = null,
    val lastLocationSyncAt: Long? = null
)
