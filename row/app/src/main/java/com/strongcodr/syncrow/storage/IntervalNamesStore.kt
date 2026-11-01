package com.strongcodr.syncrow.storage

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Per-interval metadata the user sets on the phone: the name (names.json) and the boat class
 * (boats.json). Both travel to the cloud as one `interval_meta` point; a single pending list
 * (names_pending.json, kept under its old name) says which intervals still owe that point.
 */
object IntervalNamesStore {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "intervals")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun file(context: Context): File =
        File(dir(context), "names.json")

    private fun boatFile(context: Context): File =
        File(dir(context), "boats.json")

    private fun pendingFile(context: Context): File =
        File(dir(context), "names_pending.json")

    /** Ids whose name or boat class has not reached the cloud yet (set on rename and at run start, cleared after a successful upload). */
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

    fun load(context: Context): MutableMap<Long, String> = loadMap(file(context))

    fun get(context: Context, id: Long): String? =
        load(context)[id]

    fun set(context: Context, id: Long, name: String) {
        val map = load(context)
        map[id] = name
        saveMap(file(context), map)
    }

    /** Boat class of an interval ("2x", "8+", ...), fixed when the run started. */
    fun getBoat(context: Context, id: Long): String? =
        loadMap(boatFile(context))[id]

    fun setBoat(context: Context, id: Long, boat: String) {
        val map = loadMap(boatFile(context))
        map[id] = boat
        saveMap(boatFile(context), map)
    }

    fun delete(context: Context, id: Long) {
        val names = load(context)
        if (names.remove(id) != null) saveMap(file(context), names)
        val boats = loadMap(boatFile(context))
        if (boats.remove(id) != null) saveMap(boatFile(context), boats)
        clearPending(context, id)
    }

    private val mapAdapter by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val type = Types.newParameterizedType(
            Map::class.java,
            Long::class.javaObjectType,
            String::class.java
        )
        moshi.adapter<Map<Long, String>>(type)
    }

    private fun loadMap(f: File): MutableMap<Long, String> {
        if (!f.exists()) return mutableMapOf()
        return try {
            mapAdapter.fromJson(f.readText())?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveMap(f: File, map: Map<Long, String>) {
        f.writeText(mapAdapter.toJson(map))
    }
}
