package com.aibuilt.arin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibuilt.arin.data.ArinApp
import com.aibuilt.arin.ui.screens.BuildScreen
import com.aibuilt.arin.ui.screens.HistoryScreen
import com.aibuilt.arin.ui.screens.TokenScreen
import com.aibuilt.arin.ui.theme.ArinTheme
import com.aibuilt.arin.viewmodel.ArinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ArinApp).container
        val factory = container.vmFactory
        setContent {
            ArinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    val backStack by nav.currentBackStackEntryAsState()
                    val route = backStack?.destination?.route ?: Routes.BUILD
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                Tabs.all.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }
                    ) { pad ->
                        NavHost(
                            nav,
                            startDestination = Routes.BUILD,
                            modifier = Modifier.padding(pad)
                        ) {
                            composable(Routes.BUILD) { BuildScreen(vm = viewModel(factory = factory)) }
                            composable(Routes.HISTORY) { HistoryScreen(vm = viewModel(factory = factory)) }
                            composable(Routes.SETTINGS) { TokenScreen(vm = viewModel(factory = factory)) }
                        }
                    }
                }
            }
        }
    }
}

object Routes {
    const val BUILD = "build"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

data class TabSpec(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

object Tabs {
    val all = listOf(
        TabSpec(Routes.BUILD, "Build", Icons.Filled.BuildCircle),
        TabSpec(Routes.HISTORY, "History", Icons.Filled.History),
        TabSpec(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
    )
}
