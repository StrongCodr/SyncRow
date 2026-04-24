package com.strongcodr.syncrow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.strongcodr.syncrow.storage.SensorsStore

class SensorsViewModel(application: Application) : AndroidViewModel(application) {

    private val _sensors = MutableLiveData<List<Sensor>>(emptyList())
    val sensors: LiveData<List<Sensor>> get() = _sensors

    private var nextId = 1L

    init {
        val loaded = SensorsStore.load(application)
        val sanitized = sanitizeLoaded(loaded)
        _sensors.value = sanitized
        nextId = (sanitized.maxOfOrNull { it.id } ?: 0L) + 1
        if (sanitized != loaded) {
            SensorsStore.save(application, sanitized)
        }
    }

    private fun sanitizeLoaded(loaded: List<Sensor>): List<Sensor> {
        // Clear WIT broadcast names ("WT...") from saved labels.
        val nameCleaned = loaded.map { s ->
            val n = s.name?.trim()
            if (n != null && n.startsWith("WT", ignoreCase = true)) s.copy(name = null) else s
        }
        // Enforce the single-cox invariant on load — if the JSON file got hand-edited or
        // a prior race produced multiple cox entries, keep the first and demote the rest.
        // Without this, remapSensorsForModeSwitch would silently drop the extras.
        var seenCox = false
        return nameCleaned.map { s ->
            if (s.role == SensorRole.COX) {
                if (seenCox) s.copy(role = SensorRole.SEAT) else { seenCox = true; s }
            } else s
        }
    }

    fun addSensor(mac: String, name: String?) {
        val current = _sensors.value ?: emptyList()

        // Avoid duplicates by MAC
        if (current.any { it.mac == mac }) return

        val sensor = Sensor(
            id = nextId++,
            mac = mac,
            name = null
        )
        val updated = current + sensor
        _sensors.value = updated
        SensorsStore.save(getApplication(), updated)
    }

    fun renameSensor(id: Long, newName: String) {
        val current = _sensors.value ?: return
        val normalizedName = newName.trim().ifEmpty { null }
        val updated = current.map { sensor ->
            if (sensor.id == id) sensor.copy(name = normalizedName) else sensor
        }
        _sensors.value = updated
        SensorsStore.save(getApplication(), updated)
    }

    fun removeSensor(id: Long) {
        val current = _sensors.value ?: return
        val updated = current.filterNot { it.id == id }
        _sensors.value = updated
        SensorsStore.save(getApplication(), updated)
    }

    fun removeSensorByMac(mac: String) {
        val current = _sensors.value ?: return
        val updated = current.filterNot { it.mac == mac }
        _sensors.postValue(updated)
        SensorsStore.save(getApplication(), updated)
    }

    // Called after drag finishes with the final order
    fun setOrder(newOrder: List<Sensor>) {
        _sensors.value = newOrder
        SensorsStore.save(getApplication(), newOrder)
    }

    fun clearSensors() {
        _sensors.value = emptyList()
        SensorsStore.save(getApplication(), emptyList())
    }

    /**
     * Set the role for a sensor.
     * - Marking as COX: unmarks any existing cox (single-cox invariant), then moves the
     *   new cox to index 0 so rendering and seat numbering flow naturally.
     * - Demoting from COX back to SEAT: moves the former cox to the END of the list
     *   (next to stroke, seatIndex=1). This avoids silently promoting the sensor to
     *   bow, which is what would happen if we left it at index 0 after demotion.
     */
    fun setRole(id: Long, role: SensorRole) {
        val current = _sensors.value ?: return
        if (current.none { it.id == id }) return

        val updated = when (role) {
            SensorRole.COX -> {
                val cleared = current.map { s ->
                    when {
                        s.id == id -> s.copy(role = SensorRole.COX)
                        s.role == SensorRole.COX -> s.copy(role = SensorRole.SEAT)
                        else -> s
                    }
                }
                val cox = cleared.first { it.id == id }
                val others = cleared.filter { it.id != id }
                listOf(cox) + others
            }
            SensorRole.SEAT -> {
                val target = current.first { it.id == id }
                val wasCox = target.role == SensorRole.COX
                val demoted = target.copy(role = SensorRole.SEAT)
                if (wasCox) {
                    // Move to end (stroke position) rather than leaving at index 0 (bow).
                    current.filter { it.id != id } + demoted
                } else {
                    current.map { s -> if (s.id == id) demoted else s }
                }
            }
        }

        _sensors.postValue(updated)
        SensorsStore.save(getApplication(), updated)
    }

    fun coxSensor(): Sensor? = _sensors.value?.firstOrNull { it.role == SensorRole.COX }
}
