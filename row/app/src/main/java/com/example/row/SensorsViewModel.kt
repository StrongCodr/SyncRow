package com.example.row

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.row.storage.SensorsStore

class SensorsViewModel(application: Application) : AndroidViewModel(application) {

    private val _sensors = MutableLiveData<List<Sensor>>(emptyList())
    val sensors: LiveData<List<Sensor>> get() = _sensors

    private var nextId = 1L

    init {
        val loaded = SensorsStore.load(application)
        val sanitized = loaded.map { s ->
            val n = s.name?.trim()
            // Don’t keep default WIT broadcast names like "WT..." as a saved label.
            val shouldClear = n != null && n.startsWith("WT", ignoreCase = true)
            if (shouldClear) s.copy(name = null) else s
        }
        _sensors.value = sanitized
        nextId = (sanitized.maxOfOrNull { it.id } ?: 0L) + 1
        if (sanitized != loaded) {
            SensorsStore.save(application, sanitized)
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
}
