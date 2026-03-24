package com.livelens.translator.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.livelens.translator.ui.history.HistoryScreen
import com.livelens.translator.ui.home.HomeScreen
import com.livelens.translator.ui.image.ImageTranslationScreen
import com.livelens.translator.ui.modelsetup.ModelSetupScreen
import com.livelens.translator.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home         : Screen("home")
    object Image        : Screen("image")
    object History      : Screen("history")
    object Settings     : Screen("settings")
    object ModelSetup   : Screen("model_setup")
}

@Composable
fun LiveLensNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToImage = { navController.navigate(Screen.Image.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToModelSetup = { navController.navigate(Screen.ModelSetup.route) }
            )
        }

        composable(Screen.Image.route) {
            ImageTranslationScreen(
                onNavigateUp = { navController.navigateUp() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateUp = { navController.navigateUp() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateUp = { navController.navigateUp() },
                onNavigateToModelSetup = { navController.navigate(Screen.ModelSetup.route) }
            )
        }

        composable(Screen.ModelSetup.route) {
            ModelSetupScreen(
                onNavigateUp = { navController.navigateUp() }
            )
        }
    }
}
