package com.ia.smallhome.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ia.smallhome.MainActivity
import com.ia.smallhome.R
import com.ia.smallhome.SmallHomeApplication
import com.ia.smallhome.model.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PanelConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager get() = (application as SmallHomeApplication).container.connectionManager

    override fun onCreate() {
        super.onCreate()
        if (!canUseConnectedDeviceService(this)) {
            stopSelf()
            return
        }
        createChannel()
        showForeground("Preparando la conexión…", false)
        manager.start()
        serviceScope.launch {
            manager.connectionState.collect { state ->
                val connected = state.phase == ConnectionPhase.Connected
                showForeground(
                    if (connected) "Conectado por BLE a ${state.bleName.ifBlank { "SmartPanel" }}" else state.message,
                    connected,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECONNECT -> manager.reconnect()
            ACTION_REDISCOVER -> manager.scan()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stopInBackground()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showForeground(text: String, connected: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (connected) android.R.drawable.presence_online else android.R.drawable.presence_offline)
            .setContentTitle(if (connected) "SmartPanel conectado" else "Reconectando SmartPanel…")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.connection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.connection_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "small_home_connection"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_RECONNECT = "com.ia.smallhome.RECONNECT"
        private const val ACTION_REDISCOVER = "com.ia.smallhome.REDISCOVER"

        fun start(context: Context): Boolean {
            if (!canUseConnectedDeviceService(context)) return false
            ContextCompat.startForegroundService(context, Intent(context, PanelConnectionService::class.java))
            return true
        }

        private fun canUseConnectedDeviceService(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
