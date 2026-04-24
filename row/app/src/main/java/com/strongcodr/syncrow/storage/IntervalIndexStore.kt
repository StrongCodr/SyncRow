package com.strongcodr.syncrow.storage

import android.content.Context
import com.strongcodr.syncrow.model.IntervalMeta
import com.strongcodr.syncrow.model.SyncStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

object IntervalIndexStore {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "intervals")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(context: Context): File = File(dir(context), "index.json")

    fun load(context: Context): List<IntervalMeta> {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return try {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val type = Types.newParameterizedType(List::class.java, IntervalMeta::class.java)
            val adapter = moshi.adapter<List<IntervalMeta>>(type)
            val raw = adapter.fromJson(f.readText()) ?: emptyList()
            dedupeAndSort(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsert(context: Context, meta: IntervalMeta) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == meta.id }
        if (idx >= 0) list[idx] = meta else list.add(0, meta) // newest first
        save(context, list)
    }

    fun updateStatus(context: Context, id: Long, newStatus: SyncStatus) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = list[idx]
            val syncedAt = if (newStatus == SyncStatus.SYNCED) System.currentTimeMillis() else old.lastSensorSyncAt
            list[idx] = old.copy(syncStatus = newStatus, lastSensorSyncAt = syncedAt)
            save(context, list)
        }
    }

    fun updateLocationStatus(context: Context, id: Long, newStatus: SyncStatus) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = list[idx]
            val syncedAt = if (newStatus == SyncStatus.SYNCED) System.currentTimeMillis() else old.lastLocationSyncAt
            list[idx] = old.copy(locationSyncStatus = newStatus, lastLocationSyncAt = syncedAt)
            save(context, list)
        }
    }

    fun updateDiagStatus(context: Context, id: Long, newStatus: SyncStatus) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = list[idx]
            val syncedAt = if (newStatus == SyncStatus.SYNCED) System.currentTimeMillis() else old.lastDiagSyncAt
            list[idx] = old.copy(diagSyncStatus = newStatus, lastDiagSyncAt = syncedAt)
            save(context, list)
        }
    }

    fun delete(context: Context, id: Long) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list.removeAt(idx)
            save(context, list)
        }
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, list: List<IntervalMeta>) {
        val f = indexFile(context)
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val type = Types.newParameterizedType(List::class.java, IntervalMeta::class.java)
        val adapter = moshi.adapter<List<IntervalMeta>>(type)
        f.writeText(adapter.toJson(dedupeAndSort(list)))
    }

    private fun dedupeAndSort(list: List<IntervalMeta>): List<IntervalMeta> {
        // Keep the most recent record per id; order newest first
        val byId = mutableMapOf<Long, IntervalMeta>()
        list.forEach { meta ->
            val existing = byId[meta.id]
            if (existing == null || meta.endTimeMillis > existing.endTimeMillis) {
                byId[meta.id] = meta
            }
        }
        return byId.values.sortedByDescending { it.startTimeMillis }
    }
}
