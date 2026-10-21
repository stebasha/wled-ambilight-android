package dev.wledambilight.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.wledambilight.R
import dev.wledambilight.core.data.AppContainer
import dev.wledambilight.core.model.DiagnosticSeverity
import dev.wledambilight.ui.screens.ControllerScreen
import dev.wledambilight.ui.screens.DevicesScreen
import dev.wledambilight.ui.screens.DiagnosticsScreen
import dev.wledambilight.ui.screens.HomeScreen
import dev.wledambilight.ui.screens.MoreScreen
import dev.wledambilight.ui.screens.OnboardingScreen
import dev.wledambilight.ui.screens.SettingsScreen
import dev.wledambilight.ui.screens.TuneScreen
import dev.wledambilight.ui.screens.ZonesScreen
import dev.wledambilight.ui.theme.BrandGradient
import dev.wledambilight.ui.theme.WledAmbilightTheme

/** Top-level destinations: five fit a phone bottom bar and a TV rail without scrolling. */
enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Rounded.Home),
    DEVICES("devices", R.string.nav_devices, Icons.Rounded.Devices),
    ZONES("zones", R.string.nav_zones, Icons.Rounded.Dashboard),
    TUNE("tune", R.string.nav_tune, Icons.Rounded.Tune),
    MORE("more", R.string.nav_more, Icons.Rounded.MoreHoriz),
}

/** Secondary screens reached from "More"; the MORE tab stays highlighted for them. */
object SubRoute {
    const val CONTROLLER = "controller"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"
    val all = setOf(CONTROLLER, DIAGNOSTICS, SETTINGS)
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
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (onboardingComplete) {
                null -> LoadingScreen()
                false -> OnboardingScreen(
                    onFinish = { viewModel.finishOnboarding() },
                    onOpenDevices = {
                        startOnDevices = true
                        viewModel.finishOnboarding()
                    },
                    onTryDemo = {
                        viewModel.finishOnboarding()
                        viewModel.startDemo()
                    },
                )
                true -> MainNavigation(
                    viewModel = viewModel,
                    startDestination = if (startOnDevices) Destination.DEVICES.route else Destination.HOME.route,
                )
            }
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
    val selectedTab = if (currentRoute in SubRoute.all) Destination.MORE.route else currentRoute
    val events by viewModel.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTelevision = (context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType
        ?: Configuration.UI_MODE_TYPE_NORMAL) == Configuration.UI_MODE_TYPE_TELEVISION
    val expanded = configuration.screenWidthDp >= 840 || isTelevision ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenWidthDp >= 600)

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
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // TV overscan: keep content away from the physical bezel.
                .padding(if (isTelevision) 24.dp else 0.dp),
        ) {
            NavigationRail(
                modifier = Modifier.widthIn(min = 96.dp).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                header = { BrandMark() },
            ) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Destination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = selectedTab == destination.route,
                            onClick = { navigate(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                AppNavHost(viewModel, navController, startDestination, Modifier.fillMaxSize())
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = selectedTab == destination.route,
                            onClick = { navigate(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes), maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            AppNavHost(viewModel, navController, startDestination, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier
            .padding(top = 8.dp)
            .size(44.dp)
            .background(BrandGradient, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun AppNavHost(
    viewModel: AppViewModel,
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    fun go(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(Destination.HOME.route) {
            HomeScreen(
                viewModel = viewModel,
                onOpenDevices = { go(Destination.DEVICES) },
                onOpenZones = { go(Destination.ZONES) },
                onOpenDiagnostics = { navController.navigate(SubRoute.DIAGNOSTICS) },
            )
        }
        composable(Destination.DEVICES.route) { DevicesScreen(viewModel = viewModel) }
        composable(Destination.ZONES.route) {
            ZonesScreen(viewModel = viewModel, onOpenDevices = { go(Destination.DEVICES) })
        }
        composable(Destination.TUNE.route) { TuneScreen(viewModel = viewModel) }
        composable(Destination.MORE.route) {
            MoreScreen(onOpen = { route -> navController.navigate(route) })
        }
        composable(SubRoute.CONTROLLER) { ControllerScreen(viewModel = viewModel) }
        composable(SubRoute.DIAGNOSTICS) { DiagnosticsScreen(viewModel = viewModel) }
        composable(SubRoute.SETTINGS) { SettingsScreen(viewModel = viewModel) }
    }
}
