package com.aishell.navigation

import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aishell.feature.ai.AiBottomSheet
import com.aishell.feature.settings.SettingsScreen
import com.aishell.feature.terminal.TerminalScreen

sealed class Screen(val route: String) {
    object Terminal : Screen("terminal")
    object Sessions : Screen("sessions")
    object Files : Screen("files")
    object Devices : Screen("devices")
    object Settings : Screen("settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiShellNavigation(
    navController: NavHostController = rememberNavController()
) {
    var showAiSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NavHost(
        navController = navController,
        startDestination = Screen.Terminal.route
    ) {
        composable(Screen.Terminal.route) {
            TerminalScreen(
                onOpenAi = { showAiSheet = true },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Sessions.route) {
            // TODO: SessionScreen (B5)
            Text("Sessions - TODO")
        }

        composable(Screen.Files.route) {
            // TODO: FileBrowserScreen (B5)
            Text("Files - TODO")
        }

        composable(Screen.Devices.route) {
            // TODO: DeviceScreen (B5)
            Text("Devices - TODO")
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }

    // AI Bottom Sheet overlay
    if (showAiSheet) {
        AiBottomSheet(
            sheetState = sheetState,
            onDismiss = { showAiSheet = false }
        )
    }
}
