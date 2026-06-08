package com.example.nudgev0

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.nudgev0.data.NudgeRepository

class ScrollViewModelFactory(
    private val application: Application,
    private val repository: NudgeRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScrollViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScrollViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
