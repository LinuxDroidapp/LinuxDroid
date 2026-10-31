package com.linuxdroid.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.linuxdroid.app.ui.screens.*

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Environments : Screen("environments")
    object Terminal : Screen("terminal/{environmentId}?cmd={cmd}") {
        fun route(environmentId: String, cmd: String? = null): String {
            return if (cmd != null) {
                val encoded = java.net.URLEncoder.encode(cmd, "UTF-8")
                "terminal/$environmentId?cmd=$encoded"
            } else {
                "terminal/$environmentId"
            }
        }
    }
    object Desktop : Screen("desktop/{environmentId}") {
        fun route(environmentId: String) = "desktop/$environmentId"
    }
    object Settings : Screen("settings")
    object Diagnostics : Screen("diagnostics")
    object About : Screen("about")
    object PackageManager : Screen("package_manager/{environmentId}") {
        fun route(environmentId: String) = "package_manager/$environmentId"
    }
    object Antigravity : Screen("antigravity/{environmentId}") {
        fun route(environmentId: String) = "antigravity/$environmentId"
    }
    object AntigravityAuthCode : Screen("antigravity_auth_code/{environmentId}") {
        fun route(environmentId: String) = "antigravity_auth_code/$environmentId"
    }
    object AntigravityWebView : Screen("antigravity_webview/{environmentId}?url={url}") {
        fun route(environmentId: String, url: String): String {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            return "antigravity_webview/$environmentId?url=$encoded"
        }
    }
    object Codespaces : Screen("codespaces?environmentId={environmentId}") {
        fun route(environmentId: String? = null) = if (environmentId != null) "codespaces?environmentId=$environmentId" else "codespaces"
    }
}

/**
 * Top-level navigation graph for LinuxDroid.
 */
@Composable
fun LinuxDroidNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Environments.route) {
            EnvironmentListScreen(navController = navController)
        }
        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument("environmentId") { type = NavType.StringType },
                navArgument("cmd") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            TerminalScreen(navController = navController)
        }
        composable(
            route = Screen.Desktop.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) {
            DesktopScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(navController = navController)
        }
        composable(Screen.About.route) {
            AboutScreen(navController = navController)
        }
        composable(
            route = Screen.PackageManager.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId") ?: ""
            PackageManagerScreen(environmentId = envId, navController = navController)
        }
        composable(
            route = Screen.Antigravity.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId")
            AntigravityScreen(navController = navController, environmentId = envId)
        }
        composable(
            route = Screen.AntigravityAuthCode.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId")
            AntigravityAuthCodeScreen(navController = navController, environmentId = envId)
        }
        composable(
            route = Screen.AntigravityWebView.route,
            arguments = listOf(
                navArgument("environmentId") { type = NavType.StringType },
                navArgument("url") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId") ?: ""
            val rawUrl = backStackEntry.arguments?.getString("url") ?: ""
            val decodedUrl = runCatching { java.net.URLDecoder.decode(rawUrl, "UTF-8") }.getOrDefault(rawUrl)
            AntigravityWebViewScreen(
                navController = navController,
                environmentId = envId,
                initialUrl = decodedUrl
            )
        }
        composable(
            route = Screen.Codespaces.route,
            arguments = listOf(
                navArgument("environmentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId")
            CodespacesScreen(navController = navController, activeEnvironmentId = envId)
        }
    }
}
