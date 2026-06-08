package com.customblocker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.customblocker.ui.blocklist.BlockListScreen
import com.customblocker.ui.conditions.ConditionsScreen
import com.customblocker.ui.picker.AppPickerScreen
import com.customblocker.ui.profiles.ProfileListScreen

object Routes {
    const val PROFILES = "profiles"
    const val PROFILE = "profile/{profileId}"
    const val PICKER = "picker/{profileId}"
    const val CONDITIONS = "conditions/{profileId}"

    fun profile(id: Long) = "profile/$id"
    fun picker(id: Long) = "picker/$id"
    fun conditions(id: Long) = "conditions/$id"
}

private val profileIdArg = listOf(navArgument("profileId") { type = NavType.LongType })

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfileListScreen(onOpenProfile = { id -> navController.navigate(Routes.profile(id)) })
        }
        composable(Routes.PROFILE, arguments = profileIdArg) { entry ->
            val id = entry.arguments?.getLong("profileId") ?: 0L
            BlockListScreen(
                onAddApps = { navController.navigate(Routes.picker(id)) },
                onOpenConditions = { navController.navigate(Routes.conditions(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PICKER, arguments = profileIdArg) {
            AppPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CONDITIONS, arguments = profileIdArg) {
            ConditionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
