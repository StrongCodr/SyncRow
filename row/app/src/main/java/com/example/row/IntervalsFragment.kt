package com.example.row

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.row.model.IntervalMeta
import com.example.row.model.SessionSummary
import com.example.row.model.SyncStatus
import com.example.row.storage.IntervalIndexStore
import com.example.row.storage.IntervalNamesStore
import com.example.row.viewmodel.IntervalsViewModel
import java.io.File
import android.os.Handler
import android.os.Looper
import java.util.Date

class IntervalsFragment : Fragment() {

    private val intervalsViewModel: IntervalsViewModel by activityViewModels()
    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            intervalsViewModel.refresh(requireContext())
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val scroll = android.widget.ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            addView(
                container,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        intervalsViewModel.intervals.observe(viewLifecycleOwner) { metas ->
            render(metas)
        }
    }

    override fun onResume() {
        super.onResume()
        intervalsViewModel.refresh(requireContext())
    }

    override fun onStart() {
        super.onStart()
        handler.post(pollRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(pollRunnable)
    }

    private fun render(metas: List<IntervalMeta>) {
        container.removeAllViews()

        // Header row: "History:" + Delete all intervals
        container.addView(renderHeader(metas))

        if (metas.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "No intervals yet"
                textSize = 16f
                setPadding(8, 16, 8, 8)
            })
            return
        }

        val total = metas.size
        metas.forEachIndexed { index, meta ->
            container.addView(renderRow(total - index, meta))
        }
    }

    private fun renderHeader(metas: List<IntervalMeta>): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 12)
        }

        val title = TextView(requireContext()).apply {
            text = "History:"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val syncBtn = Button(requireContext()).apply {
            text = "Sync now"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { IntervalRecordingService.syncPending(requireContext()) }
        }

        val deleteAllBtn = Button(requireContext()).apply {
            text = "Delete all intervals"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                if (metas.isEmpty()) return@setOnClickListener
                confirmDeleteAll(metas)
            }
        }

        row.addView(title)
        row.addView(syncBtn)
        row.addView(deleteAllBtn)
        return row
    }

    private fun renderRow(displayNumber: Int, meta: IntervalMeta): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 10, 8, 10)
        }

        val left = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Default name: Interval N (N based on list position)
        val defaultName = "Interval $displayNumber"
        val name = IntervalNamesStore.get(requireContext(), meta.id) ?: defaultName

        val title = TextView(requireContext()).apply {
            text = name
            textSize = 16f
        }

        val duration = formatDuration(meta.endTimeMillis - meta.startTimeMillis)
        val sensorStatus = statusText(meta.syncStatus, meta.lastSensorSyncAt)
        val locationStatus = statusText(meta.locationSyncStatus, meta.lastLocationSyncAt)

        val subtitle = TextView(requireContext()).apply {
            text = "Duration $duration • Sensor: $sensorStatus • Location: $locationStatus"
            textSize = 13f
        }

        left.addView(title)
        left.addView(subtitle)

        loadSummary(meta.id)?.let { summary ->
            val recap = formatRecap(summary)
            if (recap.isNotEmpty()) {
                left.addView(TextView(requireContext()).apply {
                    text = recap
                    textSize = 13f
                    setPadding(0, 6, 0, 0)
                })
            }
        }

        val menuBtn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            background = null
            contentDescription = "Interval options"
            setOnClickListener { showMenu(it, meta, defaultName) }
        }

        row.addView(left)
        row.addView(menuBtn)
        return row
    }

    private fun showMenu(anchor: View, meta: IntervalMeta, defaultName: String) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add("Rename")
        popup.menu.add("Remove")

        popup.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "Rename" -> {
                    renameInterval(meta, defaultName)
                    true
                }
                "Remove" -> {
                    removeInterval(meta)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun renameInterval(meta: IntervalMeta, defaultName: String) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(IntervalNamesStore.get(requireContext(), meta.id) ?: defaultName)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename interval")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    IntervalNamesStore.set(requireContext(), meta.id, name)
                    intervalsViewModel.refresh(requireContext())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeInterval(meta: IntervalMeta) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove interval?")
            .setMessage("This will remove the interval from History and delete its saved data on this phone.")
            .setPositiveButton("Remove") { _, _ ->
                // ✅ THIS was the missing piece: remove from index
                IntervalIndexStore.delete(requireContext(), meta.id)

                // remove rename if any
                IntervalNamesStore.delete(requireContext(), meta.id)

                // delete stored interval payload files + summary
                deleteIntervalFiles(meta.id)

                intervalsViewModel.refresh(requireContext())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAll(metas: List<IntervalMeta>) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete all intervals?")
            .setMessage("This will delete all intervals from History and remove their saved data on this phone.")
            .setPositiveButton("Delete all") { _, _ ->
                // delete all payload files + names
                metas.forEach { meta ->
                    deleteIntervalFiles(meta.id)
                    IntervalNamesStore.delete(requireContext(), meta.id)
                }

                // clear the index
                IntervalIndexStore.clear(requireContext())

                intervalsViewModel.refresh(requireContext())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDuration(ms: Long): String {
        val sec = (ms.coerceAtLeast(0) / 1000).toInt()
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun loadSummary(intervalId: Long): SessionSummary? {
        val f = File(File(requireContext().filesDir, "intervals"), "summary_${intervalId}.json")
        if (!f.exists()) return null
        return try {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            moshi.adapter(SessionSummary::class.java).fromJson(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun formatRecap(summary: SessionSummary): String {
        if (summary.seats.isEmpty()) return ""
        return summary.seats
            .sortedBy { it.seatIndex }
            .joinToString(separator = " • ") { seat ->
                "${seat.displayName}: avg ${seat.avgSpm} spm, max ${seat.maxSpm}"
            }
    }

    private fun deleteIntervalFiles(intervalId: Long) {
        val dir = File(requireContext().filesDir, "intervals")
        if (!dir.exists()) return

        // summary
        File(dir, "summary_${intervalId}.json").delete()
        File(dir, "location_${intervalId}.json").delete()

        // payloads (new format: interval_<id>_<sensor>.json)
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("interval_${intervalId}_") && f.name.endsWith(".json")) {
                f.delete()
            }
            // legacy single-file format
            if (f.name == "interval_${intervalId}.json") {
                f.delete()
            }
        }
    }

    private fun statusText(status: SyncStatus?, lastSyncAt: Long?): String {
        val base = when (status) {
            null -> "N/A"
            SyncStatus.PENDING -> "Syncing"
            SyncStatus.SYNCING -> "Syncing"
            SyncStatus.SYNCED -> "Synced"
            SyncStatus.FAILED -> "Failed"
        }
        if (status != SyncStatus.SYNCED || lastSyncAt == null) return base
        val time = android.text.format.DateFormat.getTimeFormat(requireContext())
            .format(Date(lastSyncAt))
        return "$base ($time)"
    }

}
