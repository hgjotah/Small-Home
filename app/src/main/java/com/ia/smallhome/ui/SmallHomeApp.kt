package com.ia.smallhome.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmallHomeApp(viewModel: SmallHomeViewModel, requestRuntimePermissions: () -> Unit) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshNotificationAccess()
                viewModel.refreshRuntimePermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    if (!preferences.onboardingComplete) {
        OnboardingScreen(viewModel, requestRuntimePermissions)
        return
    }

    val mainScreens = setOf(AppScreen.Home, AppScreen.Notifications, AppScreen.HomeAssistant, AppScreen.Settings)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (screen !in mainScreens) {
                CenterAlignedTopAppBar(
                    title = { Text(screen.title()) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.navigate(AppScreen.Settings) }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Volver")
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(AppScreen.Home, Icons.Outlined.Home, "Inicio"),
                    Triple(AppScreen.Notifications, Icons.Outlined.Notifications, "Avisos"),
                    Triple(AppScreen.HomeAssistant, Icons.AutoMirrored.Outlined.List, "Hogar"),
                    Triple(AppScreen.Settings, Icons.Outlined.Settings, "Ajustes"),
                ).forEach { (destination, icon, label) ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { viewModel.navigate(destination) },
                        icon = { Icon(icon, label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                AppScreen.Home -> HomeScreen(viewModel)
                AppScreen.Notifications -> NotificationsScreen(viewModel)
                AppScreen.HomeAssistant -> HomeAssistantScreen(viewModel)
                AppScreen.Settings -> SettingsScreen(viewModel)
                AppScreen.Crypto -> CryptoScreen(viewModel)
                AppScreen.Ai -> AiScreen(viewModel)
                AppScreen.Provision -> ProvisionScreen(viewModel, requestRuntimePermissions)
            }
        }
    }
}

private fun AppScreen.title(): String = when (this) {
    AppScreen.Crypto -> "Criptomoneda"
    AppScreen.Ai -> "OpenRouter"
    AppScreen.Provision -> "Añadir panel"
    else -> "Small Home"
}
