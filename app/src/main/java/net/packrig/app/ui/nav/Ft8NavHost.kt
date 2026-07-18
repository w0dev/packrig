package net.packrig.app.ui.nav

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.packrig.app.LogViewModel
import net.packrig.app.OperateViewModel
import net.packrig.app.SnackbarEvent
import net.packrig.app.SnackbarThrottle
import net.packrig.app.settings.SettingsScreen
import net.packrig.app.ui.log.LogScreen
import net.packrig.app.ui.operate.OperateScreen
import net.packrig.app.ui.spectrum.SpectrumScreen
import net.packrig.app.ui.theme.PackRigTheme

@Composable
fun PackRigApp(
    operateVm: OperateViewModel = viewModel(),
    logVm: LogViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val operateState by operateVm.state.collectAsStateWithLifecycle()

    // Single app-level host for OperateViewModel snackbar events. The events
    // flow has no replay, so a per-tab collector meant messages fired while
    // any other tab was open were silently dropped (2026-07-04 field report:
    // "ADIF backup written" was never visible on Settings).
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarThrottle = remember { SnackbarThrottle() }
    LaunchedEffect(Unit) {
        operateVm.events.collect { event ->
            if (snackbarThrottle.shouldShow(event.text)) {
                snackbarHostState.showSnackbar(
                    message = event.text,
                    duration = event.tag.duration,
                    // Errors linger 10 s each while the queue drains — let the
                    // operator flick them away (2026-07-03 field report).
                    withDismissAction = event.tag == SnackbarEvent.Tag.ERROR,
                )
            }
        }
    }

    PackRigTheme(darkTheme = operateState.useDarkTheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    Ft8Destination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (dest == Ft8Destination.Log && operateState.contactCount > 0) {
                                    BadgedBox(badge = { Badge { Text("${operateState.contactCount}") } }) {
                                        Icon(dest.icon, contentDescription = dest.label)
                                    }
                                } else {
                                    Icon(dest.icon, contentDescription = dest.label)
                                }
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Ft8Destination.Operate.route,
                // Consume the scaffold insets so the per-tab inner Scaffolds
                // don't re-apply the system bar insets (doubled gap above the
                // tab bar and below the status bar on every tab).
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                composable(Ft8Destination.Operate.route) {
                    OperateScreen(
                        vm = operateVm,
                        onNavigateToSettings = {
                            navController.navigate(Ft8Destination.Settings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Ft8Destination.Spectrum.route) {
                    SpectrumScreen(vm = operateVm)
                }
                composable(Ft8Destination.Log.route) {
                    LogScreen(
                        vm = logVm,
                        lastAdifBackupAtMs = operateState.lastAdifBackupAtMs,
                        onBackupNow = operateVm::backupAdifNow,
                        onImportAdif = operateVm::importAdif,
                    )
                }
                composable(Ft8Destination.Settings.route) {
                    SettingsScreen(vm = operateVm)
                }
            }
        }
        if (FirstLaunchLicense.shows(operateState.settingsLoaded, operateState.licenseAcknowledged)) {
            FirstLaunchLicenseDialog(
                onEnableTx = {
                    FirstLaunchLicense.applyChoice(true, operateVm::acknowledgeLicense, operateVm::setTxEnabled)
                },
                onRxOnly = {
                    FirstLaunchLicense.applyChoice(false, operateVm::acknowledgeLicense, operateVm::setTxEnabled)
                },
            )
        }
    }
}
