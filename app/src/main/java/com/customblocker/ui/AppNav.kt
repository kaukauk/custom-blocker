package com.customblocker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.customblocker.ui.blocklist.BlockListScreen
import com.customblocker.ui.conditions.ConditionsScreen
import com.customblocker.ui.picker.AppPickerScreen

object Routes {
    const val HOME = "home"
    const val PICKER = "picker"
    const val CONDITIONS = "conditions"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            BlockListScreen(
                onAddApps = { navController.navigate(Routes.PICKER) },
                onOpenConditions = { navController.navigate(Routes.CONDITIONS) },
            )
        }
        composable(Routes.PICKER) {
            AppPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CONDITIONS) {
            ConditionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
