package com.example.nudgev0

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.nudgev0.data.AppScrollDao
import com.example.nudgev0.data.ScrollDao
import com.example.nudgev0.data.UnlockDao

class ScrollViewModelFactory(
    private val application: Application,
    private val scrollDao: ScrollDao,
    private val unlockDao: UnlockDao,
    private val appScrollDao: AppScrollDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScrollViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScrollViewModel(application, scrollDao, unlockDao, appScrollDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
