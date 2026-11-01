package com.strongcodr.syncrow.storage

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

object IntervalNamesStore {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "intervals")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun file(context: Context): File =
        File(dir(context), "names.json")

    private fun pendingFile(context: Context): File =
        File(dir(context), "names_pending.json")

    /** Ids whose name has not reached the cloud yet (set on rename, cleared after a successful upload). */
    fun pending(context: Context): MutableSet<Long> {
        val f = pendingFile(context)
        if (!f.exists()) return mutableSetOf()
        return try {
            f.readText().split(",").filter { it.isNotBlank() }.map { it.trim().toLong() }.toMutableSet()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    fun markPending(context: Context, id: Long) {
        val p = pending(context); p.add(id); pendingFile(context).writeText(p.joinToString(","))
    }

    fun clearPending(context: Context, id: Long) {
        val p = pending(context); p.remove(id); pendingFile(context).writeText(p.joinToString(","))
    }

    fun load(context: Context): MutableMap<Long, String> {
        val f = file(context)
        if (!f.exists()) return mutableMapOf()

        return try {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val type = Types.newParameterizedType(
                Map::class.java,
                Long::class.javaObjectType,
                String::class.java
            )
            val adapter = moshi.adapter<Map<Long, String>>(type)
            adapter.fromJson(f.readText())?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun get(context: Context, id: Long): String? =
        load(context)[id]

    fun set(context: Context, id: Long, name: String) {
        val map = load(context)
        map[id] = name
        save(context, map)
    }

    fun delete(context: Context, id: Long) {
        val map = load(context)
        map.remove(id)
        save(context, map)
        clearPending(context, id)
    }

    private fun save(context: Context, map: Map<Long, String>) {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val type = Types.newParameterizedType(
            Map::class.java,
            Long::class.javaObjectType,
            String::class.java
        )
        val adapter = moshi.adapter<Map<Long, String>>(type)
        file(context).writeText(adapter.toJson(map))
    }
}
