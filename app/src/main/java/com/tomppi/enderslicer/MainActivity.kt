package com.tomppi.enderslicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.ui.EnderSlicerApp
import com.tomppi.enderslicer.ui.EnderSlicerTheme
import com.tomppi.enderslicer.ui.ErrorLogExporter
import com.tomppi.enderslicer.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnderSlicerTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    EnderSlicerApp(viewModel)
                    ErrorLogExporter(
                        viewModel = viewModel,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 88.dp),
                    )
                }
            }
        }
    }
}
