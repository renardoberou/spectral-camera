package com.renardoberou.spectralcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import com.renardoberou.spectralcamera.ui.SpectralCameraApp as SpectralCameraRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: SpectralViewModel = viewModel()
            SpectralCameraRoot(viewModel = viewModel)
        }
    }
}
