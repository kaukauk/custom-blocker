package com.customblocker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.customblocker.ui.blocklist.BlockListScreen
import com.customblocker.ui.picker.AppPickerScreen

object Routes {
    const val HOME = "home"
    const val PICKER = "picker"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            BlockListScreen(onAddApps = { navController.navigate(Routes.PICKER) })
        }
        composable(Routes.PICKER) {
            AppPickerScreen(onBack = { navController.popBackStack() })
        }
    }
}
