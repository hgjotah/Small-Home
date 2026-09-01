package com.ia.smallhome.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ia.smallhome.SmallHomeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as SmallHomeApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val canConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                if (canConnect && app.container.settingsStore.snapshot().chipId.isNotBlank()) {
                    runCatching { PanelConnectionService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
