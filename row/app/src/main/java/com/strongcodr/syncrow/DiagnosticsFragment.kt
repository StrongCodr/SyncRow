package com.strongcodr.syncrow

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.strongcodr.syncrow.model.SensorDiagnostic
import com.strongcodr.syncrow.storage.DiagnosticsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {

    // Per-sensor view holder. Kept across ticks so we update text in place instead of
    // thrashing the view tree at 1 Hz. The map is keyed by MAC; a sensor that disappears
    // from the latest row set has its holder removed (and its view detached).
    private data class RowHolder(
        val root: LinearLayout,
        val title: TextView,
        val line1: TextView,
        val line2: TextView
    )

    private val holders = mutableMapOf<String, RowHolder>()
    private var container: LinearLayout? = null
    private var status: TextView? = null
    private var tickJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.diag_container)
        status = view.findViewById(R.id.diag_status)
    }

    override fun onResume() {
        super.onResume()
        tickJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val ctx = context
                if (ctx != null) {
                    val rows = withContext(Dispatchers.IO) { DiagnosticsStore.readLatest(ctx) }
                    render(rows)
                }
                delay(1000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        tickJob?.cancel()
        tickJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        holders.clear()
        container = null
        status = null
    }

    private fun render(rows: List<SensorDiagnostic>) {
        val c = container ?: return
        if (rows.isEmpty()) {
            status?.text = "No diagnostic data on disk yet. Connect a sensor to start the 1 Hz collector."
            if (c.childCount > 0) c.removeAllViews()
            holders.clear()
            return
        }

        val tsMs = rows.maxOf { it.timestampMs }
        val ageMs = (System.currentTimeMillis() - tsMs).coerceAtLeast(0L)
        val stale = ageMs > 3000L
        val intervalLabel = rows.firstOrNull { it.intervalId != -1L }?.intervalId?.let { "interval $it" }
            ?: "idle"
        val staleTag = if (stale) "  •  STALE" else ""
        status?.text = "Last tick ${ageMs / 1000}s ago  •  $intervalLabel  •  ${rows.size} sensor(s)$staleTag"

        val ordered = rows.sortedWith(compareBy({ it.seat ?: it.sensorMac }, { it.sensorMac }))

        // Detach and forget any holder whose sensor is no longer present.
        val currentMacs = ordered.map { it.sensorMac }.toHashSet()
        val toRemove = holders.keys.filter { it !in currentMacs }
        toRemove.forEach { mac ->
            holders.remove(mac)?.let { c.removeView(it.root) }
        }

        // Place each holder at its target position in the container and bind current row.
        ordered.forEachIndexed { targetIdx, row ->
            val holder = holders[row.sensorMac] ?: createHolder().also { holders[row.sensorMac] = it }
            val currentIdx = c.indexOfChild(holder.root)
            if (currentIdx == -1) {
                c.addView(holder.root, targetIdx)
            } else if (currentIdx != targetIdx) {
                c.removeView(holder.root)
                c.addView(holder.root, targetIdx)
            }
            bindHolder(holder, row, stale)
        }
    }

    private fun createHolder(): RowHolder {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (8 * density).toInt()
        val margin = (4 * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
        }
        val title = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.START
        }
        val line1 = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        val line2 = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        root.addView(title)
        root.addView(line1)
        root.addView(line2)
        return RowHolder(root, title, line1, line2)
    }

    private fun bindHolder(holder: RowHolder, row: SensorDiagnostic, stale: Boolean) {
        holder.root.setBackgroundColor(bgColorFor(row, stale))

        val name = row.seat ?: row.sensorId ?: row.sensorMac
        // Three config states: applied (healthy), failed (permanently broken for this
        // connection), pending (in-flight during connect). "Pending" is neutral; "failed"
        // is loud — they must not share a label or color.
        val state = when {
            !row.connected -> "DISCONNECTED"
            row.configFailed -> "connected • CONFIG FAILED"
            !row.configApplied -> "connecting • config pending"
            else -> "connected"
        }
        holder.title.text = "$name  •  $state"

        // Use signed drop % — negative = surplus (sensor faster than target). Preserve the
        // sign so mis-configured rates show up instead of being silently clamped to zero.
        holder.line1.text = buildString {
            append("rx ").append(row.received).append(" / ").append(row.expected).append(" Hz")
            append("  •  drop ").append("%+.1f".format(row.dropPct)).append('%')
            append("  •  max gap ").append(row.maxGapMs).append("ms")
            append("  •  jitter ").append("%.1f".format(row.jitterMs)).append("ms")
        }

        val rssiStr = row.rssi?.let { "$it dBm" } ?: "—"
        val gattStr = row.lastGattStatus?.let { "status $it" } ?: "status OK"
        holder.line2.text = buildString {
            append("rssi ").append(rssiStr)
            append("  •  malformed ").append(row.malformed)
            append("  •  reconnects ").append(row.reconnectsThisWindow)
            append("  •  ").append(gattStr)
            append("  •  window ").append(row.windowDurationMs).append("ms")
        }
    }

    private fun bgColorFor(row: SensorDiagnostic, stale: Boolean): Int {
        if (stale) return Color.parseColor("#ECEFF1")               // grey — data isn't fresh
        if (!row.connected) return Color.parseColor("#EEEEEE")      // lighter grey — link down
        if (row.configFailed) return Color.parseColor("#FFCDD2")    // red-ish — real failure
        if (!row.configApplied) return Color.parseColor("#FFF8E1")  // soft yellow — pending, healthy
        // Absolute drop — surplus (negative) is fine, not a failure.
        val absDrop = kotlin.math.abs(row.dropPct)
        return when {
            absDrop < 2.0 -> Color.parseColor("#E8F5E9")
            absDrop < 10.0 -> Color.parseColor("#FFF8E1")
            else -> Color.parseColor("#FFEBEE")
        }
    }
}
