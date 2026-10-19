package dev.wledambilight.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.wledambilight.R
import dev.wledambilight.core.data.AppContainer
import dev.wledambilight.core.model.DiagnosticSeverity
import dev.wledambilight.ui.screens.CalibrationScreen
import dev.wledambilight.ui.screens.ColorScreen
import dev.wledambilight.ui.screens.DevicesScreen
import dev.wledambilight.ui.screens.DiagnosticsScreen
import dev.wledambilight.ui.screens.HomeScreen
import dev.wledambilight.ui.screens.OnboardingScreen
import dev.wledambilight.ui.screens.SettingsScreen
import dev.wledambilight.ui.theme.WledAmbilightTheme

enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Rounded.Home),
    DEVICES("devices", R.string.nav_devices, Icons.Rounded.Devices),
    CALIBRATION("calibration", R.string.nav_calibration, Icons.Rounded.Straighten),
    COLOR("color", R.string.nav_color, Icons.Rounded.Tune),
    DIAGNOSTICS("diagnostics", R.string.nav_diagnostics, Icons.Rounded.MonitorHeart),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
fun AmbilightAppRoot(container: AppContainer) {
    val viewModel: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppViewModel.factory(container),
    )
    val config by viewModel.config.collectAsStateWithLifecycle()
    WledAmbilightTheme(theme = config.settings.theme) {
        val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
        var startOnDevices by remember { mutableStateOf(false) }
        when (onboardingComplete) {
            null -> LoadingScreen()
            false -> OnboardingScreen(
                onFinish = { viewModel.finishOnboarding() },
                onOpenDevices = {
                    startOnDevices = true
                    viewModel.finishOnboarding()
                },
            )
            true -> MainNavigation(
                viewModel = viewModel,
                startDestination = if (startOnDevices) Destination.DEVICES.route else Destination.HOME.route,
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MainNavigation(viewModel: AppViewModel, startDestination: String) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val events by viewModel.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTelevision = (context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType
        ?: Configuration.UI_MODE_TYPE_NORMAL) == Configuration.UI_MODE_TYPE_TELEVISION
    val expanded = configuration.screenWidthDp >= 840 || isTelevision

    LaunchedEffect(events.lastOrNull()?.id) {
        val latest = events.lastOrNull() ?: return@LaunchedEffect
        if (latest.severity == DiagnosticSeverity.INFO) return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(latest.code.titleRes()))
    }

    fun navigate(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (expanded) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            NavigationRail(
                modifier = Modifier.widthIn(min = 88.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Destination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = currentRoute == destination.route,
                        onClick = { navigate(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                AppNavHost(
                    viewModel = viewModel,
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                )
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navigate(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            },
        ) { padding ->
            AppNavHost(
                viewModel = viewModel,
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    viewModel: AppViewModel,
    navController: androidx.navigation.NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(Destination.HOME.route) {
            HomeScreen(
                viewModel = viewModel,
                onOpenDevices = { navController.navigate(Destination.DEVICES.route) },
                onOpenDiagnostics = { navController.navigate(Destination.DIAGNOSTICS.route) },
            )
        }
        composable(Destination.DEVICES.route) {
            DevicesScreen(viewModel = viewModel)
        }
        composable(Destination.CALIBRATION.route) {
            CalibrationScreen(viewModel = viewModel)
        }
        composable(Destination.COLOR.route) {
            ColorScreen(viewModel = viewModel)
        }
        composable(Destination.DIAGNOSTICS.route) {
            DiagnosticsScreen(viewModel = viewModel)
        }
        composable(Destination.SETTINGS.route) {
            SettingsScreen(viewModel = viewModel)
        }
    }
}
