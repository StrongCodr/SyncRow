package com.example.row

import android.content.Context

object SeatMappingVersionStore {
    private const val PREFS = "syncrow_prefs"
    private const val KEY_SEAT_MAPPING_VERSION = "seat_mapping_version"

    fun get(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SEAT_MAPPING_VERSION, 0)
    }

    fun bump(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_SEAT_MAPPING_VERSION, 0) + 1
        prefs.edit().putInt(KEY_SEAT_MAPPING_VERSION, next).apply()
        return next
    }
}

