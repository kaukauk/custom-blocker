package com.customblocker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.customblocker.ui.blocklist.BlockListScreen
import com.customblocker.ui.conditions.ConditionsScreen
import com.customblocker.ui.map.MapPickerScreen
import com.customblocker.ui.picker.AppPickerScreen
import com.customblocker.ui.profiles.ProfileListScreen
import com.customblocker.ui.settings.SettingsScreen
import com.customblocker.ui.stats.StatsScreen
import com.customblocker.ui.strict.StrictModeScreen
import com.customblocker.ui.websites.WebsiteScreen

object Routes {
    const val PROFILES = "profiles"
    const val PROFILE = "profile/{profileId}"
    const val PICKER = "picker/{profileId}"
    const val CONDITIONS = "conditions/{profileId}?expand={expand}"
    const val MAP = "map/{profileId}?geoId={geoId}"
    const val WEBSITES = "websites/{profileId}"
    const val STRICT = "strict"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    fun profile(id: Long) = "profile/$id"
    fun picker(id: Long) = "picker/$id"
    fun conditions(id: Long, expand: Int? = null) =
        "conditions/$id" + (expand?.let { "?expand=$it" } ?: "")
    fun map(id: Long, geoId: Long? = null) =
        "map/$id" + (geoId?.let { "?geoId=$it" } ?: "")
    fun websites(id: Long) = "websites/$id"
}

private val profileIdArg = listOf(navArgument("profileId") { type = NavType.LongType })

@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfileListScreen(
                onOpenProfile = { id -> navController.navigate(Routes.profile(id)) },
                onOpenStrictMode = { navController.navigate(Routes.STRICT) },
                onOpenStats = { navController.navigate(Routes.STATS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PROFILE, arguments = profileIdArg) { entry ->
            val id = entry.arguments?.getLong("profileId") ?: 0L
            BlockListScreen(
                onAddApps = { navController.navigate(Routes.picker(id)) },
                onOpenConditions = { expand -> navController.navigate(Routes.conditions(id, expand)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PICKER, arguments = profileIdArg) {
            AppPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.CONDITIONS,
            arguments = listOf(
                navArgument("profileId") { type = NavType.LongType },
                navArgument("expand") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("profileId") ?: 0L
            val expand = entry.arguments?.getInt("expand") ?: 0
            ConditionsScreen(
                onBack = { navController.popBackStack() },
                onOpenMap = { geoId -> navController.navigate(Routes.map(id, geoId)) },
                initialExpanded = expand.takeIf { it != 0 },
            )
        }
        composable(
            Routes.MAP,
            arguments = listOf(
                navArgument("profileId") { type = NavType.LongType },
                navArgument("geoId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            MapPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.WEBSITES, arguments = profileIdArg) {
            WebsiteScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.STRICT) {
            StrictModeScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
