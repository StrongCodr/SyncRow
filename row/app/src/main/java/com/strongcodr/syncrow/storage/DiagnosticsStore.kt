package com.strongcodr.syncrow.storage

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.strongcodr.syncrow.model.SensorDiagnostic
import java.io.File

object DiagnosticsStore {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val rowAdapter = moshi.adapter(SensorDiagnostic::class.java)
    private val listAdapter = moshi.adapter<List<SensorDiagnostic>>(
        Types.newParameterizedType(List::class.java, SensorDiagnostic::class.java)
    )

    private fun latestDir(context: Context): File {
        val d = File(context.filesDir, "diagnostics")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun latestFile(context: Context): File = File(latestDir(context), "latest.json")

    private fun intervalsDir(context: Context): File {
        val d = File(context.filesDir, "intervals")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun intervalFile(context: Context, intervalId: Long): File =
        File(intervalsDir(context), "diag_${intervalId}.jsonl")

    /**
     * Overwrite the per-sensor latest snapshot atomically. Read by DiagnosticsFragment
     * every second. Contains every sensor's most recent row, not a history.
     *
     * App internal storage is always a single filesystem, so rename(2) is atomic with
     * respect to concurrent readers — if the rename ever fails, something is deeply
     * wrong (permissions, disk full) and we surface the IOException rather than pretend.
     */
    fun writeLatest(context: Context, rows: List<SensorDiagnostic>) {
        val target = latestFile(context)
        val tmp = File(target.parentFile, "latest.json.tmp")
        tmp.writeText(listAdapter.toJson(rows))
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("Failed to rename ${tmp.path} -> ${target.path}")
        }
    }

    fun readLatest(context: Context): List<SensorDiagnostic> {
        val f = latestFile(context)
        if (!f.exists()) return emptyList()
        return try {
            listAdapter.fromJson(f.readText()) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Append rows to the per-interval JSONL file. One JSON object per line.
     * Readers must tolerate (skip) malformed lines in case of crash mid-write.
     */
    fun appendInterval(context: Context, intervalId: Long, rows: List<SensorDiagnostic>) {
        if (rows.isEmpty()) return
        val f = intervalFile(context, intervalId)
        val sb = StringBuilder()
        rows.forEach { sb.append(rowAdapter.toJson(it)).append('\n') }
        f.appendText(sb.toString())
    }

    fun readInterval(context: Context, intervalId: Long): List<SensorDiagnostic> {
        val f = intervalFile(context, intervalId)
        if (!f.exists()) return emptyList()
        val result = ArrayList<SensorDiagnostic>()
        f.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    rowAdapter.fromJson(line)?.let { result.add(it) }
                } catch (_: Exception) {
                    // skip malformed line (partial write from prior crash)
                }
            }
        }
        return result
    }

    fun deleteInterval(context: Context, intervalId: Long) {
        intervalFile(context, intervalId).delete()
    }
}
