package com.ia.smallhome.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ia.smallhome.model.ClimateEntity
import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.model.CryptoAsset
import com.ia.smallhome.model.HomeAssistantEntity
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelRules
import com.ia.smallhome.ui.theme.AiPurple
import com.ia.smallhome.ui.theme.CryptoGold
import com.ia.smallhome.ui.theme.Primary
import com.ia.smallhome.ui.theme.Secondary
import com.ia.smallhome.ui.theme.Success

private val pagePadding = 18.dp

@Composable
fun HomeScreen(viewModel: SmallHomeViewModel) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val access by viewModel.notificationAccess.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val connected = connection.phase == ConnectionPhase.Connected

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeading("Small Home", "Tu panel doméstico, de un vistazo") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    if (connected) Primary.copy(alpha = .24f) else Secondary.copy(alpha = .14f),
                                    MaterialTheme.colorScheme.surface,
                                ),
                            ),
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(50.dp).background(if (connected) Primary.copy(.16f) else Secondary.copy(.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Settings, null, tint = if (connected) Primary else Secondary, modifier = Modifier.size(28.dp)) }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preferences.deviceName.ifBlank { "Small Home" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(connection.message, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        StatusPill(if (connected) "Conectado" else "Sin conexión", connected)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("Bluetooth", if (connection.bonded) "Vinculado" else "Sin vincular", Modifier.weight(1f))
                        Metric("Wi‑Fi ESP", if (device.wifiConnected) device.wifiRssi?.let(::rssiLabel) ?: "Conectado" else "Sin conexión", Modifier.weight(1f))
                        Metric("Avisos", device.notificationCount.toString(), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Text("Estado de servicios", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ServiceTile("Home Assistant", preferences.hasHaToken || secrets.homeAssistant, Icons.Outlined.Home, Success, Modifier.weight(1f)) {
                        viewModel.navigate(AppScreen.HomeAssistant)
                    }
                    ServiceTile("CoinMarketCap", preferences.hasCmcKey || secrets.coinMarketCap, Icons.Outlined.Star, CryptoGold, Modifier.weight(1f)) {
                        viewModel.navigate(AppScreen.Crypto)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ServiceTile("OpenRouter", secrets.openRouter && preferences.openRouterModel.isNotBlank(), Icons.Outlined.Star, AiPurple, Modifier.weight(1f)) {
                        viewModel.navigate(AppScreen.Ai)
                    }
                    ServiceTile("Notificaciones", access, Icons.Outlined.Notifications, Primary, Modifier.weight(1f)) {
                        viewModel.navigate(AppScreen.Notifications)
                    }
                }
            }
        }
        if (preferences.chipId.isBlank()) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.AddCircle, null, tint = Primary)
                        Column(Modifier.weight(1f)) {
                            Text("Añade tu panel", fontWeight = FontWeight.SemiBold)
                            Text("Empareja el ESP32‑C6 por BLE y envíale su Wi‑Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LargeAction("Añadir Small Home") { viewModel.navigate(AppScreen.Provision) }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ServiceTile(label: String, configured: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = color)
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(if (configured) "Configurado" else "Pendiente", color = if (configured) Success else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun rssiLabel(rssi: Int): String = when {
    rssi >= -55 -> "Excelente"
    rssi >= -67 -> "Buena"
    rssi >= -75 -> "Media"
    else -> "Débil"
}

@Composable
fun NotificationsScreen(viewModel: SmallHomeViewModel) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val access by viewModel.notificationAccess.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, search) { apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) } }
    val selected = apps.count { it.enabled }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeading("Notificaciones", "Elige qué aplicaciones pueden aparecer en la TFT") }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Notifications, null, tint = if (access) Success else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (access) "Acceso concedido" else "Acceso necesario", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (access) "Small Home puede sincronizar los avisos permitidos." else "Android debe permitir leer y descartar notificaciones.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (access) "Revisar acceso del sistema" else "Conceder acceso") }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                label = { Text("Buscar aplicación") },
                supportingText = { Text("$selected aplicaciones seleccionadas") },
            )
        }
        if (filtered.isEmpty()) {
            item {
                SectionCard { Text("No hay aplicaciones que coincidan. Abre una notificación de esa app y vuelve aquí para añadirla.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.toggleApp(app.packageName, !app.enabled) }.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName, app.label)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.Medium)
                            Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = app.enabled, onCheckedChange = { viewModel.toggleApp(app.packageName, it) })
                    }
                }
            }
        }
        item { Text("Small Home nunca reenvía su propia notificación de servicio. Al pulsar CENTRO en la TFT intentará primero “marcar como leída” si la app ofrece esa acción y después descartará el aviso.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun HomeAssistantScreen(viewModel: SmallHomeViewModel, compact: Boolean = false) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val entities by viewModel.haEntities.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var entitySearch by rememberSaveable { mutableStateOf("") }
    var selectedLights by remember { mutableStateOf<List<LightEntity>>(emptyList()) }
    var climate by remember { mutableStateOf<ClimateEntity?>(null) }
    var selectionError by remember { mutableStateOf("") }
    LaunchedEffect(preferences.haBaseUrl, preferences.lights, preferences.climate) {
        if (url.isBlank()) url = preferences.haBaseUrl
        if (selectedLights.isEmpty()) selectedLights = preferences.lights
        if (climate == null) climate = preferences.climate
    }
    val catalog = remember(entities.entities, selectedLights, climate) {
        val storedEntities = selectedLights.map {
            HomeAssistantEntity(
                id = it.id,
                name = it.name,
                domain = it.id.substringBefore('.', "sin_dominio"),
                state = "guardada",
            )
        } + listOfNotNull(
            climate?.let {
                HomeAssistantEntity(
                    id = it.id,
                    name = it.name,
                    domain = it.id.substringBefore('.', "sin_dominio"),
                    state = "guardada",
                )
            },
        )
        (entities.entities + storedEntities).distinctBy { it.id }
    }
    val matchingEntities = remember(catalog, entitySearch) {
        catalog.filter {
            it.name.contains(entitySearch, true) ||
                it.id.contains(entitySearch, true) ||
                it.domain.contains(entitySearch, true) ||
                it.state.contains(entitySearch, true)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (!compact) item { PageHeading("Home Assistant", "El ESP32‑C6 controla directamente tus luces y termostato") }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Home, null, tint = Success)
                    Spacer(Modifier.width(10.dp))
                    Text("Conexión", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    StatusPill(if (secrets.homeAssistant || preferences.hasHaToken) "Configurado" else "Sin configurar", secrets.homeAssistant || preferences.hasHaToken)
                }
                OutlinedTextField(url, { url = it }, label = { Text("URL base") }, placeholder = { Text("http://192.168.1.20:8123") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SecretField(token, { token = it }, "Long-Lived Access Token", secrets.homeAssistant)
                OutlinedButton(onClick = { viewModel.testHomeAssistant(url, token) }, enabled = !operation.loading, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cargar entidades desde Android")
                }
                OutlinedButton(
                    onClick = viewModel::testHomeAssistantFromPanel,
                    enabled = connection.phase == ConnectionPhase.Connected && !operation.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Probar Home Assistant desde SmartPanel") }
                OperationBanner(operation)
            }
        }
        if (catalog.isNotEmpty()) {
            item {
                Text("Todas las entidades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${catalog.size} entidades · ${selectedLights.size}/10 como luz · ${if (climate == null) "sin termostato" else "1 como termostato"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    entitySearch,
                    { entitySearch = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    label = { Text("Buscar cualquier entidad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(matchingEntities, key = { it.id }) { entity ->
                val selectedLight = selectedLights.firstOrNull { it.id == entity.id }
                val selectedClimate = climate?.takeIf { it.id == entity.id }
                val role = when {
                    selectedLight != null -> EntityRole.Light
                    selectedClimate != null -> EntityRole.Thermostat
                    else -> EntityRole.None
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Outlined.List,
                                null,
                                tint = when (role) {
                                    EntityRole.Light -> Primary
                                    EntityRole.Thermostat -> Secondary
                                    EntityRole.None -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entity.name, fontWeight = FontWeight.Medium)
                                Text(entity.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                entity.domain,
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Estado actual: ${entity.state}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = role == EntityRole.None,
                                onClick = {
                                    selectedLights = selectedLights.filterNot { it.id == entity.id }
                                    if (climate?.id == entity.id) climate = null
                                    selectionError = ""
                                },
                                label = { Text("No usar") },
                            )
                            FilterChip(
                                selected = role == EntityRole.Light,
                                onClick = {
                                    if (role != EntityRole.Light && selectedLights.size >= 10) {
                                        selectionError = "El firmware admite un máximo de 10 entidades catalogadas como luz."
                                    } else {
                                        val displayName = selectedLight?.name ?: selectedClimate?.name ?: entity.name
                                        if (climate?.id == entity.id) climate = null
                                        selectedLights = selectedLights.filterNot { it.id == entity.id } +
                                            LightEntity(entity.id, displayName)
                                        selectionError = ""
                                    }
                                },
                                label = { Text("Luz") },
                            )
                            FilterChip(
                                selected = role == EntityRole.Thermostat,
                                onClick = {
                                    val displayName = selectedClimate?.name ?: selectedLight?.name ?: entity.name
                                    selectedLights = selectedLights.filterNot { it.id == entity.id }
                                    climate = ClimateEntity(entity.id, displayName)
                                    selectionError = ""
                                },
                                label = { Text("Termostato") },
                            )
                        }
                        if (role != EntityRole.None) {
                            val alias = selectedLight?.name ?: selectedClimate?.name.orEmpty()
                            OutlinedTextField(
                                value = alias,
                                onValueChange = { newName ->
                                    val limitedName = newName.take(40)
                                    when (role) {
                                        EntityRole.Light -> selectedLights = selectedLights.map {
                                            if (it.id == entity.id) it.copy(name = limitedName) else it
                                        }
                                        EntityRole.Thermostat -> climate = climate?.copy(name = limitedName)
                                        EntityRole.None -> Unit
                                    }
                                },
                                label = { Text("Nombre en la TFT") },
                                supportingText = { Text("Solo cambia el nombre enviado al ESP32‑C6; Home Assistant no se modifica.") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = { viewModel.testHomeAssistantEntity(entity.id) },
                                enabled = connection.phase == ConnectionPhase.Connected && !operation.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Probar entidad en SmartPanel") }
                        }
                    }
                }
            }
            if (matchingEntities.isEmpty()) item { SectionCard { Text("No hay entidades que coincidan con la búsqueda.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            item { SectionCard { Text("Carga las entidades de Home Assistant para verlas todas y catalogarlas manualmente.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        if (selectionError.isNotBlank()) item { Text(selectionError, color = MaterialTheme.colorScheme.error) }
        item {
            Text(
                "Puedes catalogar cualquier entidad como luz (ON/OFF) o una como termostato (objetivo ±0,5 °C). La entidad debe ofrecer los servicios correspondientes en Home Assistant.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            LargeAction(
                label = "Guardar en Small Home",
                enabled = connection.phase == ConnectionPhase.Connected && !operation.loading,
            ) {
                val fallbackNames = catalog.associate { it.id to it.name }
                val cleanLights = selectedLights
                    .filterNot { it.id == climate?.id }
                    .map { it.copy(name = it.name.trim().ifBlank { fallbackNames[it.id] ?: it.id }) }
                val cleanClimate = climate?.let { it.copy(name = it.name.trim().ifBlank { fallbackNames[it.id] ?: it.id }) }
                viewModel.saveHomeAssistant(url, token, cleanLights, cleanClimate)
            }
        }
        if (connection.phase != ConnectionPhase.Connected) item { Text("El botón se habilitará cuando el panel esté conectado.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private enum class EntityRole { None, Light, Thermostat }

@Composable
fun CryptoScreen(viewModel: SmallHomeViewModel, compact: Boolean = false) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val results by viewModel.cryptoAssets.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<CryptoAsset?>(null) }
    var fiat by rememberSaveable { mutableStateOf("EUR") }
    LaunchedEffect(preferences.cmcId, preferences.cmcSymbol, preferences.fiat) {
        selected = preferences.cmcId.takeIf { it > 0 }?.let { CryptoAsset(it, preferences.cmcSymbol, preferences.cmcSymbol) }
        fiat = preferences.fiat
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (!compact) item { PageHeading("Criptomoneda", "Configura la consulta manual de CoinMarketCap") }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Star, null, tint = CryptoGold)
                    Spacer(Modifier.width(10.dp))
                    Text("CoinMarketCap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    StatusPill(if (secrets.coinMarketCap || preferences.hasCmcKey) "API configurada" else "Falta API key", secrets.coinMarketCap || preferences.hasCmcKey)
                }
                SecretField(key, { key = it }, "CoinMarketCap API Key", secrets.coinMarketCap)
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Nombre o símbolo") },
                    placeholder = { Text("XDAG") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { IconButton(onClick = { viewModel.searchCrypto(query, key) }) { Icon(Icons.Outlined.Search, "Buscar") } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OperationBanner(operation)
            }
        }
        if (results.isNotEmpty()) {
            item { Text("Resultados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(results, key = { it.id }) { asset ->
                val isSelected = selected?.id == asset.id
                Card(colors = CardDefaults.cardColors(containerColor = if (isSelected) CryptoGold.copy(.12f) else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().clickable { selected = asset }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(CryptoGold.copy(.15f), CircleShape), contentAlignment = Alignment.Center) { Text(asset.symbol.take(3), color = CryptoGold, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(asset.name, fontWeight = FontWeight.Medium); Text("${asset.symbol} · CMC ID: ${asset.id}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        RadioButton(isSelected, onClick = { selected = asset })
                    }
                }
            }
        }
        selected?.let { asset ->
            item {
                SectionCard {
                    Text("Activo elegido", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${asset.name} · ${asset.symbol}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("CMC ID: ${asset.id}", color = CryptoGold)
                    Text("Divisa", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelRules.SUPPORTED_FIAT.forEach { value -> FilterChip(selected = fiat == value, onClick = { fiat = value }, label = { Text(value) }) }
                    }
                }
            }
            item {
                LargeAction("Guardar criptomoneda", connection.phase == ConnectionPhase.Connected && !operation.loading) {
                    viewModel.saveCrypto(key, asset, fiat)
                }
            }
        }
        item {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = CryptoGold)
                    Text("El precio solo se actualizará cuando pulses IZQUIERDA + CENTRO en la pantalla principal bloqueada del panel. Android nunca envía precios periódicos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun AiScreen(viewModel: SmallHomeViewModel, compact: Boolean = false) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(preferences.openRouterModel) { if (model.isBlank()) model = preferences.openRouterModel }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (!compact) item { PageHeading("Inteligencia artificial", "OpenRouter se ejecuta exclusivamente en Android") }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Star, null, tint = AiPurple)
                    Spacer(Modifier.width(10.dp))
                    Text("OpenRouter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    StatusPill(if (secrets.openRouter && model.isNotBlank()) "Configurado" else "Pendiente", secrets.openRouter && model.isNotBlank())
                }
                SecretField(key, { key = it }, "OpenRouter API Key", secrets.openRouter)
                OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, placeholder = { Text("proveedor/modelo") }, supportingText = { Text("Se acepta cualquier ID válido de OpenRouter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { viewModel.testOpenRouter(key, model) }, enabled = !operation.loading, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Probar modelo")
                }
                OperationBanner(operation)
                LargeAction("Guardar en este teléfono", !operation.loading) { viewModel.saveOpenRouter(key, model) }
            }
        }
        item {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Lock, null, tint = AiPurple)
                    Column {
                        Text("La API key nunca sale del teléfono hacia el ESP32‑C6", fontWeight = FontWeight.SemiBold)
                        Text("Las preguntas se escriben desde los botones de Small Home. Las respuestas se muestran únicamente en la TFT y la conversación vive solo en memoria mientras esa sesión está abierta.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ProvisionScreen(
    viewModel: SmallHomeViewModel,
    requestRuntimePermissions: () -> Unit,
    compact: Boolean = false,
) {
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val candidates by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val permissions by viewModel.runtimePermissions.collectAsStateWithLifecycle()
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var panelName by rememberSaveable { mutableStateOf("Small Home") }
    LaunchedEffect(operation.success) { if (operation.success == true) password = "" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (!compact) item { PageHeading("Añadir Small Home", "Empareja el ESP32‑C6 por BLE y configura su Wi‑Fi") }
        item {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = Primary)
                    Column(Modifier.weight(1f)) {
                        Text("Emparejamiento seguro", fontWeight = FontWeight.SemiBold)
                        Text("Introduce el PIN de 6 cifras que aparece en la TFT cuando Android lo solicite.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(if (connection.bonded) "Bonded" else "Sin bond", connection.bonded)
                }
                LabelValue("Estado BLE", connection.phase.name)
                if (!permissions.bluetoothReady) {
                    OutlinedButton(onClick = requestRuntimePermissions, modifier = Modifier.fillMaxWidth()) { Text("Conceder permisos Bluetooth") }
                }
                OutlinedButton(
                    onClick = viewModel::startBleScan,
                    enabled = permissions.bluetoothReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Buscar SmartPanel")
                }
            }
        }
        if (candidates.isNotEmpty()) {
            item { Text("SmartPanel encontrados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(candidates, key = { it.address }) { candidate ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.name, fontWeight = FontWeight.SemiBold)
                            Text("${candidate.rssi} dBm · ${if (candidate.bonded) "Vinculado" else "Nuevo"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { viewModel.connectBleDevice(candidate.address) }) { Text("Conectar") }
                    }
                }
            }
        }
        item {
            SectionCard {
                Text("Wi‑Fi del SmartPanel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("BLE seguirá siendo el enlace con Android. Este Wi‑Fi solo lo usa el ESP para NTP, Home Assistant y CoinMarketCap.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID Wi‑Fi de casa (2,4 GHz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SecretField(password, { password = it }, "Contraseña Wi‑Fi de casa", false)
                OutlinedTextField(panelName, { panelName = it }, label = { Text("Nombre del panel (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OperationBanner(operation)
                LargeAction(
                    "Enviar Wi‑Fi por BLE",
                    connection.phase == ConnectionPhase.Connected && !operation.loading,
                ) { viewModel.provision(ssid, password, panelName) }
                Text("La contraseña se envía una sola vez por el enlace BLE cifrado y no se guarda en Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SmallHomeViewModel) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val synced by viewModel.syncedNotifications.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val runtimePermissions by viewModel.runtimePermissions.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    var confirmFactoryReset by remember { mutableStateOf(false) }
    var brightnessPercent by rememberSaveable { mutableFloatStateOf(43f) }
    LaunchedEffect(preferences.brightness) {
        brightnessPercent = preferences.brightness * 50f / 127f
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(pagePadding),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item { PageHeading("Configuración", "Servicios, dispositivo y diagnóstico") }
        item {
            SettingsLink(Icons.Outlined.Star, "Criptomoneda", "CoinMarketCap, activo y divisa", CryptoGold) { viewModel.navigate(AppScreen.Crypto) }
        }
        item {
            SettingsLink(Icons.Outlined.Star, "Inteligencia artificial", "API key y Model ID de OpenRouter", AiPurple) { viewModel.navigate(AppScreen.Ai) }
        }
        item {
            SettingsLink(Icons.Outlined.AddCircle, "Añadir o reconfigurar panel", "Pairing BLE y Wi‑Fi del ESP32‑C6", Primary) { viewModel.navigate(AppScreen.Provision) }
        }
        item { Text("Dispositivo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item {
            SectionCard {
                LabelValue("Nombre", preferences.deviceName)
                LabelValue("Chip ID", preferences.chipId.ifBlank { "Sin emparejar" })
                LabelValue("BLE", connection.phase.name, if (connection.phase == ConnectionPhase.Connected) Success else MaterialTheme.colorScheme.onSurface)
                LabelValue("Bonded", if (connection.bonded) "Sí" else "No")
                LabelValue("Nombre BLE", connection.bleName.ifBlank { preferences.bleName.ifBlank { "Desconocido" } })
                LabelValue("Protocolo", connection.protocol.ifBlank { preferences.protocol })
                LabelValue("Último heartbeat", connection.lastHeartbeatEpochMs?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "Sin datos")
                HorizontalDivider()
                LabelValue("Wi‑Fi del ESP", if (device.wifiConnected) "Conectado" else "Desconectado", if (device.wifiConnected) Success else MaterialTheme.colorScheme.error)
                LabelValue("SSID del ESP", device.wifiSsid.ifBlank { preferences.wifiSsid.ifBlank { "Sin configurar" } })
                LabelValue("IP Wi‑Fi del ESP", device.wifiIp.ifBlank { "Sin datos" })
                LabelValue("RSSI Wi‑Fi", device.wifiRssi?.let { "$it dBm" } ?: "Sin datos")
                LabelValue("Avisos sincronizados", synced.toString())
                LabelValue("Permiso BLE scan", if (runtimePermissions.bluetoothScan) "Concedido" else "Denegado")
                LabelValue("Permiso BLE connect", if (runtimePermissions.bluetoothConnect) "Concedido" else "Denegado")
                LabelValue("Red local (solo HA)", if (runtimePermissions.localNetwork) "Concedido" else "Denegado")
                HorizontalDivider()
                LabelValue("Home Assistant", if (device.hasHa || preferences.hasHaToken || secrets.homeAssistant) "Configurado" else "No")
                LabelValue("CoinMarketCap", if (device.hasCmc || preferences.hasCmcKey || secrets.coinMarketCap) "Configurado" else "No")
                LabelValue("OpenRouter", if (secrets.openRouter && preferences.openRouterModel.isNotBlank()) "Configurado" else "No")
                LabelValue("Flappy récord", device.flappyHighScore.toString())
                OperationBanner(operation)
                OutlinedButton(viewModel::rediscover, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(8.dp)); Text("Buscar SmartPanel por BLE") }
                OutlinedButton(viewModel::reconnect, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Reconectar BLE") }
                OutlinedButton(viewModel::refreshPanelStatus, Modifier.fillMaxWidth(), enabled = connection.phase == ConnectionPhase.Connected) { Text("Actualizar estado") }
                OutlinedButton(viewModel::refreshPanelConfig, Modifier.fillMaxWidth(), enabled = connection.phase == ConnectionPhase.Connected) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null); Spacer(Modifier.width(8.dp)); Text("Actualizar configuración desde el panel") }
                OutlinedButton(viewModel::testHomeAssistantFromPanel, Modifier.fillMaxWidth(), enabled = connection.phase == ConnectionPhase.Connected) { Text("Probar Home Assistant desde ESP") }
                OutlinedButton(onClick = { viewModel.navigate(AppScreen.Provision) }, modifier = Modifier.fillMaxWidth()) { Text("Volver a enviar Wi‑Fi") }
                OutlinedButton(viewModel::copyDiagnostics, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Copiar diagnóstico seguro") }
            }
        }
        item {
            SectionCard {
                Text("Brillo de la TFT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${brightnessPercent.toInt()}% · recomendado 43% · máximo seguro configurado 50%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = brightnessPercent,
                    onValueChange = { brightnessPercent = it },
                    valueRange = 0f..50f,
                    steps = 49,
                )
                LargeAction("Guardar brillo", connection.phase == ConnectionPhase.Connected && !operation.loading) {
                    viewModel.saveBrightness((brightnessPercent / 50f * 127f).toInt().coerceIn(0, 127))
                }
            }
        }
        item {
            SectionCard {
                Text("Restablecer conexión", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Olvidar elimina la identidad guardada por Small Home, pero Android puede conservar el bond. El restablecimiento de fábrica borra la configuración del panel y exige una confirmación adicional.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { confirmForget = true }) { Text("Olvidar emparejamiento en este teléfono", color = MaterialTheme.colorScheme.error) }
                OutlinedButton(
                    onClick = { confirmFactoryReset = true },
                    enabled = connection.phase == ConnectionPhase.Connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restablecer SmartPanel de fábrica", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            SectionCard {
                Text("Controles físicos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LabelValue("Desbloquear", "CENTRO → IZQUIERDA → DERECHA")
                LabelValue("Volver", "IZQUIERDA + DERECHA")
                LabelValue("Actualizar precio", "IZQUIERDA + CENTRO")
                LabelValue("Bloqueo automático", "60 segundos")
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("¿Olvidar el panel?") },
            text = { Text("Se eliminará la identidad BLE guardada en Small Home. Para borrar también el bond, usa los Ajustes Bluetooth de Android.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; viewModel.forgetPanel() }) { Text("Olvidar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
    if (confirmFactoryReset) {
        AlertDialog(
            onDismissRequest = { confirmFactoryReset = false },
            title = { Text("¿Borrar SmartPanel?") },
            text = { Text("Se borrarán del ESP el Wi‑Fi, Home Assistant, CoinMarketCap, nombres y brillo. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { confirmFactoryReset = false; viewModel.factoryReset() }) {
                    Text("BORRAR TODO", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFactoryReset = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SettingsLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(color.copy(.13f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
