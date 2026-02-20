// In a new file, e.g., ViewModelFactory.kt
package com.example.nudgev0

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.nudgev0.data.ScrollDao

class ScrollViewModelFactory(private val dao: ScrollDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        // Check if the ViewModel class being requested is our ScrollViewModel
        if (modelClass.isAssignableFrom(ScrollViewModel::class.java)) {
            // If it is, create an instance of it, passing the dao in the constructor.
            // The "as T" part is a required cast.
            @Suppress("UNCHECKED_CAST")
            return ScrollViewModel(dao) as T
        }
        // If it's some other ViewModel, throw an error.
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
