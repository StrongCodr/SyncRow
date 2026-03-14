package com.example.row.storage

import android.content.Context
import com.example.row.Sensor
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

object SensorsStore {

    private fun installMarker(context: Context): File =
        File(context.noBackupFilesDir, "install_marker_v1")

    private fun isFirstRunForThisInstall(context: Context): Boolean {
        val marker = installMarker(context)
        if (marker.exists()) return false
        marker.parentFile?.mkdirs()
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
        return true
    }

    private fun file(context: Context): File {
        val dir = File(context.filesDir, "sensors")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "sensors.json")
    }

    fun load(context: Context): List<Sensor> {
        val f = file(context)
        // If Android restored app data after reinstall, `filesDir` can come back but `noBackupFilesDir`
        // will be empty. Treat that case as a fresh install and start with no sensors.
        if (isFirstRunForThisInstall(context) && f.exists()) {
            runCatching { f.delete() }
            return emptyList()
        }
        if (!f.exists()) return emptyList()
        return try {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val type = Types.newParameterizedType(List::class.java, Sensor::class.java)
            val adapter = moshi.adapter<List<Sensor>>(type)
            adapter.fromJson(f.readText()) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sensors: List<Sensor>) {
        val f = file(context)
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val type = Types.newParameterizedType(List::class.java, Sensor::class.java)
        val adapter = moshi.adapter<List<Sensor>>(type)
        f.writeText(adapter.toJson(sensors))
    }
}
