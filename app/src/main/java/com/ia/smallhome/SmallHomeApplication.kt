package com.ia.smallhome

import android.app.Application
import com.google.gson.Gson
import com.ia.smallhome.ble.SmartPanelBleManager
import com.ia.smallhome.data.SettingsStore
import com.ia.smallhome.model.PanelPreferences
import com.ia.smallhome.network.AiSessionManager
import com.ia.smallhome.network.CoinMarketCapClient
import com.ia.smallhome.network.HomeAssistantClient
import com.ia.smallhome.network.OpenRouterClient
import com.ia.smallhome.network.PanelConnectionManager
import com.ia.smallhome.notifications.NotificationGateway
import com.ia.smallhome.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SmallHomeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val settingsStore = SettingsStore(application, Gson())
    val secureStore = SecureStore(application)
    val preferencesCache = MutableStateFlow(PanelPreferences())
    val notificationGateway = NotificationGateway()
    val bleManager = SmartPanelBleManager(application)
    val homeAssistantClient = HomeAssistantClient(httpClient)
    val coinMarketCapClient = CoinMarketCapClient(httpClient)
    val openRouterClient = OpenRouterClient(OpenRouterClient.defaultClient())
    val aiSessionManager = AiSessionManager(openRouterClient, secureStore, settingsStore)
    val connectionManager = PanelConnectionManager(
        context = application,
        settingsStore = settingsStore,
        notificationGateway = notificationGateway,
        aiSessionManager = aiSessionManager,
        bleManager = bleManager,
    )

    init {
        appScope.launch { settingsStore.preferences.collect { preferencesCache.value = it } }
    }
}
