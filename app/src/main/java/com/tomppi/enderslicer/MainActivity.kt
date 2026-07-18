package com.tomppi.enderslicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tomppi.enderslicer.ui.EnderSlicerApp
import com.tomppi.enderslicer.ui.EnderSlicerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnderSlicerTheme {
                EnderSlicerApp()
            }
        }
    }
}
