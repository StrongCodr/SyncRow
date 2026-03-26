package com.strongcodr.syncrow.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.strongcodr.syncrow.model.IntervalMeta
import com.strongcodr.syncrow.storage.IntervalIndexStore

class IntervalsViewModel : ViewModel() {

    private val _intervals = MutableLiveData<List<IntervalMeta>>(emptyList())
    val intervals: LiveData<List<IntervalMeta>> = _intervals

    fun refresh(context: Context) {
        _intervals.value = IntervalIndexStore.load(context)
    }
}
