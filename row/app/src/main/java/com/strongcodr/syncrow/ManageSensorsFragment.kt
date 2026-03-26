package com.strongcodr.syncrow

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.strongcodr.syncrow.databinding.FragmentManageSensorsBinding
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

private const val SCULL_SHAKE_RENDER_THROTTLE_MS = 250L
private const val DEBUG_SHAKE_THROTTLE = false

class ManageSensorsFragment : Fragment() {

    private var _binding: FragmentManageSensorsBinding? = null
    private val binding get() = _binding!!

    private val sensorsViewModel: SensorsViewModel by activityViewModels()

    private lateinit var adapter: SensorsAdapter
    private var currentMode: RowingMode = RowingMode.SWEEP
    private var highlightedScullingSensorId: Long? = null
    private var lastScullShakeRenderMs = 0L

    private var shakeModeEnabled = false
    private var dragOrderChanged = false
    private val shakeClients: MutableMap<Long, BleDeviceClient> = mutableMapOf()
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable { fadeHighlightIfAny() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManageSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SensorsAdapter(
            onMenuClick = { anchorView, sensor ->
                showSensorMenu(anchorView, sensor)
            },
            onRowClick = { _ ->
                // Row click no-op for now; shake is driven by sensor, not taps
            }
        )

        binding.recyclerSensors.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSensors.adapter = adapter

        currentMode = RowingModeStore.getUiMode(requireContext())
        binding.radioSweep.isChecked = currentMode == RowingMode.SWEEP
        binding.radioSculling.isChecked = currentMode == RowingMode.SCULLING
        binding.modeSelector.setOnCheckedChangeListener { _, checkedId ->
            val nextMode = when (checkedId) {
                R.id.radio_sculling -> RowingMode.SCULLING
                else -> RowingMode.SWEEP
            }
            if (nextMode == currentMode) return@setOnCheckedChangeListener

            val currentSensors = sensorsViewModel.sensors.value.orEmpty()
            val remapped = CrewLayout.remapSensorsForModeSwitch(
                sensors = currentSensors,
                from = currentMode,
                to = nextMode
            )
            if (remapped != currentSensors) {
                sensorsViewModel.setOrder(remapped)
                SeatMappingVersionStore.bump(requireContext())
            }
            currentMode = nextMode
            RowingModeStore.set(requireContext(), currentMode)
            renderSensors(sensorsViewModel.sensors.value.orEmpty())
        }

        // Drag & drop: adapter reorders list smoothly, ViewModel updated when drag ends
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) {
                    return false
                }
                dragOrderChanged = true
                adapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // no swipe actions
            }

            override fun isLongPressDragEnabled(): Boolean = true

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Drag finished → push final order to ViewModel
                sensorsViewModel.setOrder(adapter.getCurrentItems())
                if (dragOrderChanged) {
                    SeatMappingVersionStore.bump(requireContext())
                    dragOrderChanged = false
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerSensors)

        // Observe registered sensors
        sensorsViewModel.sensors.observe(viewLifecycleOwner) { sensors ->
            renderSensors(sensors)
        }

        // '+' button → Add Sensor screen
        binding.fabAddSensor.setOnClickListener {
            findNavController().navigate(R.id.addSensorFragment)
        }

        // Shake button – shake-aware mode for all sensors
        binding.buttonShake.setOnClickListener {
            toggleShakeMode()
        }

        binding.buttonRemoveAllSensors.setOnClickListener {
            confirmRemoveAll()
        }
    }

    private fun renderSensors(sensors: List<Sensor>) {
        if (sensors.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            binding.rowReorderHint.visibility = View.GONE
            binding.recyclerSensors.visibility = View.GONE
            binding.scrollSculling.visibility = View.GONE
        } else {
            binding.textEmpty.visibility = View.GONE
            binding.rowReorderHint.visibility = View.VISIBLE
            if (currentMode == RowingMode.SWEEP) {
                binding.recyclerSensors.visibility = View.VISIBLE
                binding.scrollSculling.visibility = View.GONE
            } else {
                binding.recyclerSensors.visibility = View.GONE
                binding.scrollSculling.visibility = View.VISIBLE
            }
        }

        if (currentMode == RowingMode.SWEEP) {
            binding.textReorderHint.text = "Tip: drag and drop to reorder."
            adapter.submitList(sensors)
        } else {
            binding.textReorderHint.text = "Tip: use the drop-down menu to reorder a sensor."
            renderScullingSeats(sensors)
        }
    }

    private fun renderScullingSeats(sensors: List<Sensor>) {
        val container = binding.containerSculling
        container.removeAllViews()

        val seats = CrewLayout.toScullingSeats(sensors)
        seats.forEach { seat ->
            val seatHighlighted = seat.port?.id == highlightedScullingSensorId ||
                seat.starboard?.id == highlightedScullingSensorId
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (seatHighlighted) R.drawable.bg_sensor_bubble_highlight else R.drawable.bg_sensor_bubble
                )
                setPadding(24, 18, 24, 18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 12, 0, 0) }
            }

            val seatTitle = TextView(requireContext()).apply {
                text = "Seat ${seat.seatNumber}"
                textSize = 18f
            }
            card.addView(seatTitle)

            card.addView(createScullingSideRow("Starboard", seat.port))
            card.addView(createScullingSideRow("Port", seat.starboard))

            if (!seat.isComplete()) {
                card.addView(TextView(requireContext()).apply {
                    text = "Warning: incomplete seat (needs Port + Starboard)"
                    textSize = 12f
                    setPadding(0, 10, 0, 0)
                })
            }

            container.addView(card)
        }
    }

    private fun createScullingSideRow(label: String, sensor: Sensor?): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 12, 0, 0)
        }

        val text = TextView(requireContext()).apply {
            textSize = 15f
            text = if (sensor == null) {
                "$label: (empty)"
            } else {
                val displayName = sensor.name?.trim()?.takeIf { it.isNotEmpty() } ?: sensor.mac
                "$label:\n  $displayName"
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(text)

        if (sensor != null) {
            val menuButton = ImageButton(requireContext()).apply {
                setImageResource(android.R.drawable.ic_menu_more)
                background = null
                contentDescription = "Sensor options"
                setOnClickListener { view -> showSensorMenu(view, sensor) }
            }
            row.addView(menuButton)
        }

        return row
    }

    private fun toggleShakeMode() {
        val ctx = requireContext()
        val sensors = sensorsViewModel.sensors.value.orEmpty()
        if (!shakeModeEnabled) {
            if (sensors.isEmpty()) {
                Toast.makeText(ctx, "No sensors registered.", Toast.LENGTH_SHORT).show()
                return
            }

            binding.buttonShake.text = "Shake (ON)"
            Toast.makeText(
                ctx,
                "Shake mode enabled.\nShake a sensor to highlight it.\nDo not cover the sensor completely or it may lose connection.",
                Toast.LENGTH_LONG
            ).show()

            adapter.setHighlightedId(null)
            highlightedScullingSensorId = null
            shakeClients.values.forEach { it.disconnect() }
            shakeClients.clear()

            // Connect to all sensors and listen for big acceleration on each
            sensors.forEach { sensor ->
                val client = BleDeviceClient(ctx)
                shakeClients[sensor.id] = client
                client.connect(
                    deviceAddress = sensor.mac,
                    onSample = { ax, ay, az, _, _, _, _, _, _ ->
                        handleShakeSample(sensor, ax, ay, az)
                    },
                    onStatus = { _ -> }
                )
            }

            shakeModeEnabled = true
        } else {
            // Turn off shake mode
            shakeModeEnabled = false
            shakeClients.values.forEach { it.disconnect() }
            shakeClients.clear()
            adapter.setHighlightedId(null)
            highlightedScullingSensorId = null
            if (currentMode == RowingMode.SCULLING) {
                renderSensors(sensorsViewModel.sensors.value.orEmpty())
            }
            binding.buttonShake.text = "Shake"
            Toast.makeText(ctx, "Shake mode disabled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleShakeSample(sensor: Sensor, ax: Float, ay: Float, az: Float) {
        if (!shakeModeEnabled) return

        val mag = sqrt(ax * ax + ay * ay + az * az)
        // Threshold: 2g as requested
        if (mag >= 2.0f) {
            requireActivity().runOnUiThread {
                if (currentMode == RowingMode.SWEEP) {
                    adapter.setHighlightedId(sensor.id)
                } else {
                    highlightedScullingSensorId = sensor.id
                    val now = SystemClock.elapsedRealtime()
                    val dt = now - lastScullShakeRenderMs
                    if (dt < SCULL_SHAKE_RENDER_THROTTLE_MS) {
                        if (DEBUG_SHAKE_THROTTLE) {
                            Log.d("SYNCROW", "Shake render throttled: dt=${dt}ms")
                        }
                        return@runOnUiThread
                    }
                    lastScullShakeRenderMs = now
                    renderSensors(sensorsViewModel.sensors.value.orEmpty())
                }
                fadeHandler.removeCallbacks(fadeRunnable)
                fadeHandler.postDelayed(fadeRunnable, 2000L)
            }
        }
    }

    private fun fadeHighlightIfAny() {
        if (currentMode == RowingMode.SCULLING) {
            if (highlightedScullingSensorId != null) {
                highlightedScullingSensorId = null
                renderSensors(sensorsViewModel.sensors.value.orEmpty())
            }
            return
        }
        val pos = adapter.getHighlightedPosition() ?: return
        val holder = binding.recyclerSensors.findViewHolderForAdapterPosition(pos) as? SensorsAdapter.SensorViewHolder
        if (holder == null) {
            adapter.setHighlightedId(null)
            return
        }
        holder.fadeOutHighlight(500L) {
            adapter.setHighlightedId(null)
        }
    }

    private fun showSensorMenu(anchor: View, sensor: Sensor) {
        val popup = PopupMenu(requireContext(), anchor)
        if (currentMode == RowingMode.SCULLING) {
            popup.menu.add("Swap Port/Starboard")
            popup.menu.add("Swap with...")
        }
        popup.menu.add("Rename")
        popup.menu.add("Remove")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Swap Port/Starboard" -> {
                    swapPortStarboard(sensor)
                    true
                }
                "Swap with..." -> {
                    showSwapDialog(sensor)
                    true
                }
                "Rename" -> {
                    showRenameDialog(sensor)
                    true
                }
                "Remove" -> {
                    showRemoveDialog(sensor)
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    private fun showSwapDialog(sensor: Sensor) {
        val sensors = sensorsViewModel.sensors.value.orEmpty()
        val targets = sensors.filter { it.id != sensor.id }
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "No other sensors to swap with.", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = targets.map {
            val displayName = it.name?.trim()?.takeIf { n -> n.isNotEmpty() } ?: it.mac
            val seat = sensors.size - sensors.indexOfFirst { s -> s.id == it.id }
            "Seat $seat: $displayName"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Swap sensor with...")
            .setItems(labels) { _, which ->
                val target = targets[which]
                swapSensors(sensor, target)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun swapSensors(first: Sensor, second: Sensor) {
        val sensors = sensorsViewModel.sensors.value.orEmpty().toMutableList()
        val firstIdx = sensors.indexOfFirst { it.id == first.id }
        val secondIdx = sensors.indexOfFirst { it.id == second.id }
        if (firstIdx < 0 || secondIdx < 0 || firstIdx == secondIdx) return

        val tmp = sensors[firstIdx]
        sensors[firstIdx] = sensors[secondIdx]
        sensors[secondIdx] = tmp
        sensorsViewModel.setOrder(sensors)
        SeatMappingVersionStore.bump(requireContext())
        Toast.makeText(requireContext(), "Sensors swapped.", Toast.LENGTH_SHORT).show()
    }

    private fun swapPortStarboard(sensor: Sensor) {
        if (currentMode != RowingMode.SCULLING) return

        val sensors = sensorsViewModel.sensors.value.orEmpty().toMutableList()
        val idx = sensors.indexOfFirst { it.id == sensor.id }
        if (idx < 0) return

        val portIdx = if (idx % 2 == 0) idx else idx - 1
        val starboardIdx = portIdx + 1
        val port = sensors.getOrNull(portIdx)
        val starboard = sensors.getOrNull(starboardIdx)
        if (port == null || starboard == null) {
            Toast.makeText(requireContext(), "Seat is incomplete. Need Port + Starboard.", Toast.LENGTH_SHORT).show()
            return
        }

        sensors[portIdx] = starboard
        sensors[starboardIdx] = port
        sensorsViewModel.setOrder(sensors)
        SeatMappingVersionStore.bump(requireContext())
        Toast.makeText(requireContext(), "Port/Starboard swapped.", Toast.LENGTH_SHORT).show()
    }

    private fun showRenameDialog(sensor: Sensor) {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(sensor.name.orEmpty())
            setSelection(text.length)
            hint = "Enter a name"
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNeutralButton("Reset") { _, _ ->
                sensorsViewModel.renameSensor(sensor.id, "")
            }
            .setNegativeButton("Cancel", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(context, "Name can’t be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sensorsViewModel.renameSensor(sensor.id, newName)
            dialog.dismiss()
        }
    }

    private fun showRemoveDialog(sensor: Sensor) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove sensor")
            .setMessage("Are you sure you want to remove this sensor?")
            .setPositiveButton("Remove") { _, _ ->
                sensorsViewModel.removeSensor(sensor.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveAll() {
        val sensors = sensorsViewModel.sensors.value.orEmpty()
        if (sensors.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle("Remove all sensors?")
            .setMessage("This will remove all sensors from this phone.")
            .setPositiveButton("Remove all") { _, _ ->
                sensorsViewModel.clearSensors()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        fadeHandler.removeCallbacks(fadeRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shakeClients.values.forEach { it.disconnect() }
        shakeClients.clear()
        shakeModeEnabled = false
        highlightedScullingSensorId = null
        binding.buttonShake.text = "Shake"
        fadeHandler.removeCallbacks(fadeRunnable)
        _binding = null
    }
}

/**
 * RecyclerView adapter for sensor bubbles.
 * Supports:
 *  - 3-dot menu per row
 *  - highlighting a single sensor by id (for shake mode)
 *  - smooth drag reordering
 */
class SensorsAdapter(
    private val onMenuClick: (View, Sensor) -> Unit,
    private val onRowClick: (Sensor) -> Unit
) : RecyclerView.Adapter<SensorsAdapter.SensorViewHolder>() {

    private val items: MutableList<Sensor> = mutableListOf()
    private var highlightedId: Long? = null

    fun submitList(newItems: List<Sensor>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getCurrentItems(): List<Sensor> = items.toList()

    fun moveItem(fromPos: Int, toPos: Int) {
        if (fromPos == toPos) return
        if (fromPos !in items.indices || toPos !in items.indices) return

        val item = items.removeAt(fromPos)
        items.add(toPos, item)
        notifyItemMoved(fromPos, toPos)
    }

    fun setHighlightedId(id: Long?) {
        highlightedId = id
        notifyDataSetChanged()
    }

    fun getHighlightedPosition(): Int? {
        val id = highlightedId ?: return null
        val idx = items.indexOfFirst { it.id == id }
        return if (idx >= 0) idx else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SensorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sensor, parent, false)
        return SensorViewHolder(view)
    }

    override fun onBindViewHolder(holder: SensorViewHolder, position: Int) {
        val sensor = items[position]
        val isHighlighted = sensor.id == highlightedId
        val seatNumber = items.size - position
        holder.bind(seatNumber, sensor, isHighlighted, onMenuClick, onRowClick)
    }

    override fun getItemCount(): Int = items.size

    class SensorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val seatView: TextView = itemView.findViewById(R.id.text_seat)
        private val nameView: TextView = itemView.findViewById(R.id.text_name)
        private val menuButton: View = itemView.findViewById(R.id.button_menu)
        private val rootRow: View = itemView.findViewById(R.id.root_row)

        fun bind(
            seatIndex: Int,
            sensor: Sensor,
            highlighted: Boolean,
            onMenuClick: (View, Sensor) -> Unit,
            onRowClick: (Sensor) -> Unit
        ) {
            val custom = sensor.name?.trim().orEmpty()
            seatView.text = if (custom.isNotEmpty()) custom else "Seat $seatIndex"
            nameView.visibility = View.GONE

            rootRow.setBackgroundResource(
                if (highlighted) R.drawable.bg_sensor_bubble_highlight
                else R.drawable.bg_sensor_bubble
            )

            menuButton.setOnClickListener { view ->
                onMenuClick(view, sensor)
            }

            rootRow.setOnClickListener {
                onRowClick(sensor)
            }
        }

        fun fadeOutHighlight(durationMs: Long, onEnd: () -> Unit) {
            rootRow.animate()
                .alpha(0f)
                .setDuration(durationMs)
                .withEndAction {
                    rootRow.setBackgroundResource(R.drawable.bg_sensor_bubble)
                    rootRow.alpha = 1f
                    onEnd()
                }
                .start()
        }
    }
}
