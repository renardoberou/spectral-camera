package com.renardoberou.spectralcamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.gl.SpectralGlView
import com.renardoberou.spectralcamera.core.state.SpectralViewModel
import com.renardoberou.spectralcamera.ui.screens.GalleryScreen
import com.renardoberou.spectralcamera.ui.screens.HardwareTestScreen
import com.renardoberou.spectralcamera.ui.screens.LiveCameraScreen
import com.renardoberou.spectralcamera.ui.theme.SpectralCameraTheme
import kotlin.math.roundToInt

private sealed class Route(val route: String, val label: String) {
    data object Live : Route("live", "Live")
    data object Gallery : Route("gallery", "Gallery")
    data object Hardware : Route("hardware", "Hardware")
}

@Composable
fun SpectralCameraApp(viewModel: SpectralViewModel) {
    SpectralCameraTheme {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val navController = rememberNavController()
        val cameraController = remember { CameraController(context) }
        val glView = remember {
            SpectralGlView(context).apply {
                onSurfaceTextureReady = cameraController::onSurfaceTextureAvailable
            }
        }
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        val gallery by viewModel.galleryItems.collectAsStateWithLifecycle()
        var capabilities by remember { mutableStateOf<CameraCapabilities?>(null) }
        var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions(context)) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            permissionsGranted = granted.values.all { it }
        }

        // Push every settings change straight into the GPU pipeline.
        LaunchedEffect(settings) {
            glView.updateSettings(settings)
        }

        // Hardware exposure compensation lives on the camera itself.
        LaunchedEffect(capabilities, settings.hardwareEv) {
            cameraController.setExposureCompensation(settings.hardwareEv.roundToInt())
        }

        // GLSurfaceView needs explicit pause/resume to keep its render thread healthy.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> glView.onResume()
                    Lifecycle.Event.ON_PAUSE -> glView.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                cameraController.release()
            }
        }

        LaunchedEffect(Unit) {
            if (!permissionsGranted) permissionLauncher.launch(requiredPermissions())
        }

        if (permissionsGranted) {
            AppShell(
                viewModel = viewModel,
                navController = navController,
                cameraController = cameraController,
                glView = glView,
                lifecycleOwner = lifecycleOwner,
                settings = settings,
                galleryCount = gallery.size,
                capabilities = capabilities,
                onCapabilities = { capabilities = it },
            )
        } else {
            PermissionGateScreen(onGrant = { permissionLauncher.launch(requiredPermissions()) })
        }
    }
}

@Composable
private fun AppShell(
    viewModel: SpectralViewModel,
    navController: NavHostController,
    cameraController: CameraController,
    glView: SpectralGlView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    settings: CameraSettings,
    galleryCount: Int,
    capabilities: CameraCapabilities?,
    onCapabilities: (CameraCapabilities) -> Unit,
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route ?: Route.Live.route

    // The small analysis stream only feeds the hardware-test screen.
    LaunchedEffect(currentRoute) {
        cameraController.setAnalysisEnabled(currentRoute == Route.Hardware.route)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(Route.Live, Route.Gallery, Route.Hardware).forEach { route ->
                    NavigationBarItem(
                        selected = currentRoute == route.route,
                        onClick = {
                            navController.navigate(route.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            when (route) {
                                Route.Live -> Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                                Route.Gallery -> Icon(Icons.Outlined.Collections, contentDescription = null)
                                Route.Hardware -> Icon(Icons.Outlined.Memory, contentDescription = null)
                            }
                        },
                        label = { Text(route.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CameraSessionHost(
                cameraController = cameraController,
                glView = glView,
                lifecycleOwner = lifecycleOwner,
                settings = settings,
                onAnalysisFrame = viewModel::onAnalysisFrame,
                onCapabilities = onCapabilities,
            )

            NavHost(
                navController = navController,
                startDestination = Route.Live.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Route.Live.route) {
                    LiveCameraScreen(
                        viewModel = viewModel,
                        cameraController = cameraController,
                        settings = settings,
                        capabilities = capabilities,
                        galleryCount = galleryCount,
                        onCapture = {
                            viewModel.captureAndSave(cameraController) { raw, captureSettings ->
                                glView.process(raw, captureSettings)
                            }
                        },
                        onOpenGallery = { navController.navigate(Route.Gallery.route) },
                        onOpenHardware = { navController.navigate(Route.Hardware.route) },
                    )
                }
                composable(Route.Gallery.route) {
                    GalleryScreen(viewModel = viewModel)
                }
                composable(Route.Hardware.route) {
                    HardwareTestScreen(
                        viewModel = viewModel,
                        cameraController = cameraController,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraSessionHost(
    cameraController: CameraController,
    glView: SpectralGlView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    settings: CameraSettings,
    onAnalysisFrame: (android.graphics.Bitmap) -> Unit,
    onCapabilities: (CameraCapabilities) -> Unit,
) {
    DisposableEffect(lifecycleOwner, settings.frontFacing) {
        cameraController.bind(
            lifecycleOwner = lifecycleOwner,
            glView = glView,
            lensFacing = if (settings.frontFacing) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
            onCapabilities = onCapabilities,
            onAnalysisFrame = onAnalysisFrame,
        )
        onDispose { }
    }

    AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun PermissionGateScreen(onGrant: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Spectral Camera")
            Text("Camera permission is required to start the simulated IR view.")
            Text("The app never claims true infrared from the internal camera — simulated IR is the default.")
            Button(onClick = onGrant) {
                Text("Grant camera permissions")
            }
        }
    }
}

private fun hasRequiredPermissions(context: Context): Boolean =
    requiredPermissions().all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
} else {
    arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
}
