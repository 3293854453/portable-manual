package com.portablemanual.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.ui.capture.CaptureScreen
import com.portablemanual.app.ui.detail.DeviceDetailScreen
import com.portablemanual.app.ui.editor.DeviceEditorScreen
import com.portablemanual.app.ui.library.LibraryScreen
import com.portablemanual.app.ui.reader.ReaderScreen
import com.portablemanual.app.ui.settings.SettingsScreen
import com.portablemanual.app.ui.tags.TagsScreen

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.MAIN) {
            MainTabsScreen(
                container = container,
                onOpenDevice = { navController.navigate(Routes.deviceDetail(it)) },
                onCreateDevice = { navController.navigate(Routes.deviceEditor(null)) }
            )
        }

        composable(
            route = Routes.DEVICE_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_DEVICE_ID) { type = NavType.LongType })
        ) { entry ->
            val deviceId = entry.arguments?.getLong(Routes.ARG_DEVICE_ID) ?: -1L
            DeviceDetailScreen(
                container = container,
                deviceId = deviceId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.deviceEditor(deviceId)) },
                onCapture = { navController.navigate(Routes.capture(deviceId)) },
                onRead = { page -> navController.navigate(Routes.reader(deviceId, page)) }
            )
        }

        composable(
            route = Routes.DEVICE_EDITOR,
            arguments = listOf(navArgument(Routes.ARG_DEVICE_ID) { type = NavType.LongType })
        ) { entry ->
            val deviceId = entry.arguments?.getLong(Routes.ARG_DEVICE_ID) ?: -1L
            DeviceEditorScreen(
                container = container,
                deviceId = deviceId.takeIf { it > 0 },
                onBack = { navController.popBackStack() },
                onSaved = { newId ->
                    navController.popBackStack()
                    if (deviceId <= 0 && newId > 0) {
                        navController.navigate(Routes.deviceDetail(newId))
                    }
                }
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(navArgument(Routes.ARG_DEVICE_ID) { type = NavType.LongType })
        ) { entry ->
            val deviceId = entry.arguments?.getLong(Routes.ARG_DEVICE_ID) ?: -1L
            CaptureScreen(
                container = container,
                deviceId = deviceId,
                onDone = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument(Routes.ARG_DEVICE_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_PAGE) { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            val deviceId = entry.arguments?.getLong(Routes.ARG_DEVICE_ID) ?: -1L
            val page = entry.arguments?.getInt(Routes.ARG_PAGE) ?: 0
            ReaderScreen(
                container = container,
                deviceId = deviceId,
                startPage = page,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** 三个主 Tab。底部导航切换用内部状态，不额外开一层 NavHost，简单可靠。 */
@Composable
private fun MainTabsScreen(
    container: AppContainer,
    onOpenDevice: (Long) -> Unit,
    onCreateDevice: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showBottomBar by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180))
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp
                ) {
                    val items = listOf(
                        Triple("资料库", Icons.Outlined.LibraryBooks, 0),
                        Triple("标签", Icons.Outlined.Label, 1),
                        Triple("设置", Icons.Outlined.Settings, 2)
                    )
                    items.forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            when (tab) {
                0 -> LibraryScreen(
                    container = container,
                    onOpenDevice = onOpenDevice,
                    onCreateDevice = onCreateDevice
                )
                1 -> TagsScreen(
                    container = container,
                    onOpenDevice = onOpenDevice
                )
                else -> SettingsScreen(
                    container = container,
                    onBottomBarVisibilityChange = { showBottomBar = it }
                )
            }
        }
    }
}
