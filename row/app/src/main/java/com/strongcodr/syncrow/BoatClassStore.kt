package com.strongcodr.syncrow

import android.content.Context

/**
 * The boat class a run is rowed in ("2x", "4-", "8+", ...). The picker next to Start Interval
 * writes the last choice here; the recording service reads it when a run starts and fixes it
 * to that interval (see IntervalNamesStore.setBoat), so changing the picker later never
 * touches a run already under way.
 */
object BoatClassStore {
    private const val PREFS = "syncrow_prefs"
    private const val KEY_BOAT_CLASS = "boat_class"
    private const val DEFAULT = "2x"

    /** Offered in this order; the strings are what goes to the cloud as the `boat` field. */
    val CLASSES: List<String> = listOf("1x", "2x", "2-", "4x", "4-", "4+", "8+")

    fun getLast(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BOAT_CLASS, DEFAULT)
        return if (raw != null && raw in CLASSES) raw else DEFAULT
    }

    fun setLast(context: Context, boat: String) {
        if (boat !in CLASSES) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOAT_CLASS, boat)
            .apply()
    }
}
