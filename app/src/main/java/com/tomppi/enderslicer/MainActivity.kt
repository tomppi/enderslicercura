package com.tomppi.enderslicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.octoprint.OctoPrintViewModel
import com.tomppi.enderslicer.ui.EnderSlicerTheme
import com.tomppi.enderslicer.ui.IntegratedEnderSlicerApp
import com.tomppi.enderslicer.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val slicerViewModel by viewModels<MainViewModel>()
    private val octoPrintViewModel by viewModels<OctoPrintViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshTriangleLimits.initialize(this)
        enableEdgeToEdge()
        setContent {
            EnderSlicerTheme {
                IntegratedEnderSlicerApp(slicerViewModel, octoPrintViewModel)
            }
        }
    }
}
