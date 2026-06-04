package net.ft8vc.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.ft8vc.app.LogViewModel
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.settings.SettingsScreen
import net.ft8vc.app.ui.log.LogScreen
import net.ft8vc.app.ui.operate.OperateScreen
import net.ft8vc.app.ui.theme.Ft8vcTheme

@Composable
fun Ft8vcApp(
    operateVm: OperateViewModel = viewModel(),
    logVm: LogViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val operateState by operateVm.state.collectAsStateWithLifecycle()

    Ft8vcTheme {
        Scaffold(
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
                modifier = Modifier.padding(padding),
            ) {
                composable(Ft8Destination.Operate.route) {
                    OperateScreen(vm = operateVm)
                }
                composable(Ft8Destination.Log.route) {
                    LogScreen(vm = logVm)
                }
                composable(Ft8Destination.Settings.route) {
                    SettingsScreen(vm = operateVm)
                }
            }
        }
    }
}
