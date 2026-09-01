package com.ia.smallhome.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ia.smallhome.R
import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.ui.theme.AiPurple
import com.ia.smallhome.ui.theme.CryptoGold
import com.ia.smallhome.ui.theme.Primary
import com.ia.smallhome.ui.theme.Secondary
import com.ia.smallhome.ui.theme.Success

private data class OnboardingPage(val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

@Composable
fun OnboardingScreen(viewModel: SmallHomeViewModel, requestRuntimePermissions: () -> Unit) {
    val context = LocalContext.current
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val access by viewModel.notificationAccess.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val runtimePermissions by viewModel.runtimePermissions.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var panelName by rememberSaveable { mutableStateOf("Small Home") }
    val pages = listOf(
        OnboardingPage("Bienvenido a Small Home", "Tu móvil y tu panel, trabajando como un solo sistema", Icons.Outlined.Home, Primary),
        OnboardingPage("Un panel que sigue funcionando", "El ESP32‑C6 controla el hogar; Android aporta avisos e IA", Icons.Outlined.Settings, Secondary),
        OnboardingPage("Permisos claros", "Solo pedimos Bluetooth, avisos y red local cuando corresponde", Icons.Outlined.Lock, Primary),
        OnboardingPage("Conecta el ESP32‑C6", "Empareja por BLE y envía su Wi‑Fi doméstica", Icons.Outlined.Info, Primary),
        OnboardingPage("Tus notificaciones", "Tú decides qué aplicaciones llegan a la TFT", Icons.Outlined.Notifications, Secondary),
        OnboardingPage("Home Assistant", "Elige hasta 10 luces y un termostato", Icons.Outlined.Home, Success),
        OnboardingPage("CoinMarketCap", "Precio bajo demanda, nunca actualizaciones automáticas", Icons.Outlined.Star, CryptoGold),
        OnboardingPage("OpenRouter", "IA privada en el teléfono y respuestas solo en la TFT", Icons.Outlined.Star, AiPurple),
        OnboardingPage("Todo listo", "Revisa el estado y termina la puesta en marcha", Icons.Outlined.CheckCircle, Success),
    )

    LaunchedEffect(operation.success) {
        if (operation.success == true && page == 3) wifiPassword = ""
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} de ${pages.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::finishOnboarding) { Text("Saltar asistente") }
            }
            LinearProgressIndicator(progress = { (page + 1f) / pages.size }, modifier = Modifier.fillMaxWidth())
            AnimatedContent(page, label = "onboarding_page", modifier = Modifier.weight(1f)) { index ->
                val item = pages[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Box(Modifier.size(82.dp).background(item.color.copy(.14f), CircleShape), contentAlignment = Alignment.Center) {
                        if (index == 0) {
                            Image(
                                painter = painterResource(R.drawable.small_home_logo),
                                contentDescription = "Logo de Small Home",
                                modifier = Modifier.size(72.dp),
                            )
                        } else {
                            Icon(item.icon, null, tint = item.color, modifier = Modifier.size(42.dp))
                        }
                    }
                    Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    when (index) {
                        0 -> InfoBlock("Small Home encuentra el panel por BLE, usa el emparejamiento seguro de Android y recupera automáticamente el estado tras una desconexión.")
                        1 -> InfoBlock("Sin el teléfono, el panel conserva hora NTP, Home Assistant y CoinMarketCap. Solo las nuevas notificaciones y OpenRouter necesitan Android.")
                        2 -> {
                            InfoBlock("Dispositivos cercanos permite escanear y conectar por BLE. La red local solo se usa si Android consulta un Home Assistant local; nunca es necesaria para hablar con SmartPanel.")
                            SectionCard {
                                SummaryRow("Escaneo Bluetooth", runtimePermissions.bluetoothScan)
                                SummaryRow("Conexión Bluetooth", runtimePermissions.bluetoothConnect)
                                SummaryRow("Red local para Home Assistant", runtimePermissions.localNetwork)
                                SummaryRow("Notificación del servicio", runtimePermissions.postNotifications)
                            }
                            Button(onClick = requestRuntimePermissions, modifier = Modifier.fillMaxWidth()) { Text("Conceder permisos de Android") }
                        }
                        3 -> {
                            SectionCard {
                                Text("Emparejamiento BLE seguro", fontWeight = FontWeight.SemiBold)
                                Text("Introduce en el diálogo de Android el PIN de 6 cifras que aparece físicamente en SmartPanel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                StatusPill(
                                    if (connection.phase == ConnectionPhase.Connected) "BLE conectado" else connection.phase.name,
                                    connection.phase == ConnectionPhase.Connected,
                                )
                                if (!runtimePermissions.bluetoothReady) {
                                    OutlinedButton(onClick = requestRuntimePermissions, modifier = Modifier.fillMaxWidth()) { Text("Conceder permisos Bluetooth") }
                                }
                                OutlinedButton(onClick = viewModel::startBleScan, modifier = Modifier.fillMaxWidth(), enabled = runtimePermissions.bluetoothReady) {
                                    Text("Buscar SmartPanel")
                                }
                                discoveredDevices.take(4).forEach { candidate ->
                                    OutlinedButton(onClick = { viewModel.connectBleDevice(candidate.address) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("${candidate.name} · ${candidate.rssi} dBm${if (candidate.bonded) " · vinculado" else ""}")
                                    }
                                }
                                if (connection.phase == ConnectionPhase.Connected) {
                                    OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID Wi‑Fi de casa (2,4 GHz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    SecretField(wifiPassword, { wifiPassword = it }, "Contraseña Wi‑Fi", false)
                                    OutlinedTextField(panelName, { panelName = it }, label = { Text("Nombre del panel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    LargeAction("Enviar Wi‑Fi por BLE", !operation.loading) { viewModel.provision(ssid, wifiPassword, panelName) }
                                }
                                OperationBanner(operation)
                            }
                        }
                        4 -> {
                            InfoBlock(if (access) "Acceso a notificaciones concedido. Después podrás elegir aplicación por aplicación." else "Concede el acceso especial y vuelve a Small Home. Ninguna app se activa automáticamente.")
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (access) "Revisar acceso" else "Abrir acceso a notificaciones") }
                        }
                        5 -> InfoBlock("Android carga todas las entidades de Home Assistant. Tú decides cuáles se usan como luces y cuál como termostato; después el propio ESP verifica la API.")
                        6 -> InfoBlock("Introduce tu API key y busca la moneda por nombre o símbolo. La app resuelve el CMC ID. El ESP solo consultará el precio cuando pulses IZQUIERDA + CENTRO.")
                        7 -> InfoBlock("La API key de OpenRouter queda cifrada en el teléfono y nunca se envía al panel. El Model ID es libre y se puede probar antes de guardar.")
                        8 -> {
                            SectionCard {
                                SummaryRow("Panel", preferences.chipId.isNotBlank() && connection.phase == ConnectionPhase.Connected)
                                SummaryRow("Acceso a notificaciones", access)
                                SummaryRow("Home Assistant", secrets.homeAssistant || preferences.hasHaToken)
                                SummaryRow("CoinMarketCap", secrets.coinMarketCap || preferences.hasCmcKey)
                                SummaryRow("OpenRouter", secrets.openRouter && preferences.openRouterModel.isNotBlank())
                            }
                            Text("Lo que falte quedará señalado en Inicio y podrás configurarlo en cualquier momento.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (page > 0) OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f)) { Text("Atrás") }
                Button(
                    onClick = { if (page == pages.lastIndex) viewModel.finishOnboarding() else page++ },
                    modifier = Modifier.weight(1f),
                ) { Text(if (page == pages.lastIndex) "Entrar en Small Home" else "Continuar") }
            }
        }
    }
}

@Composable
private fun InfoBlock(text: String) {
    SectionCard { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun SummaryRow(label: String, complete: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, null, tint = if (complete) Success else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(label, Modifier.weight(1f))
        Text(if (complete) "Listo" else "Después", color = if (complete) Success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
