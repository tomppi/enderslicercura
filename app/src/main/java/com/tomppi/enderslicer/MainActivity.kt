package com.tomppi.enderslicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tomppi.enderslicer.ui.EnderSlicerApp
import com.tomppi.enderslicer.ui.EnderSlicerTheme
import com.tomppi.enderslicer.ui.MainViewModel
import com.tomppi.enderslicer.mesh.MeshTriangleLimits

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshTriangleLimits.initialize(this)
        enableEdgeToEdge()
        setContent {
            EnderSlicerTheme {
                EnderSlicerApp(viewModel)
            }
        }
    }
}
