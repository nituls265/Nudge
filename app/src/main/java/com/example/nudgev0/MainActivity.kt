package com.example.nudgev0

import com.example.nudgev0.data.ScrollDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. Create an instance of the database for your app.
        //    "applicationContext" ensures it's shared across the whole app.
        val database = ScrollDatabase.getDatabase(applicationContext)

        // 2. Create an instance of our new ViewModel Factory, giving it the database's DAO.
        val viewModelFactory = ScrollViewModelFactory(database.scrollDao())

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // This will now turn from RED to WHITE because the function exists!
                    MainScreen(factory = viewModelFactory)
                }
            }
        }
    }
}