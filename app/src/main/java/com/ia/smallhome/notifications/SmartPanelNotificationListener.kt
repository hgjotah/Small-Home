package com.ia.smallhome.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ia.smallhome.SmallHomeApplication
import com.ia.smallhome.model.NotificationEvent
import com.ia.smallhome.model.PanelNotification
import com.ia.smallhome.model.PanelRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartPanelNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container get() = (application as SmallHomeApplication).container

    override fun onCreate() {
        super.onCreate()
        container.notificationGateway.attach(this)
    }

    override fun onDestroy() {
        container.notificationGateway.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName || notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        scope.launch {
            container.settingsStore.observePackage(notification.packageName)
            val preferences = container.settingsStore.snapshot()
            if (PanelRules.canForward(notification.packageName, packageName, preferences.allowedPackages)) {
                extract(notification)?.let { container.notificationGateway.emit(NotificationEvent.Added(it)) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        scope.launch {
            val allowed = container.settingsStore.snapshot().allowedPackages
            if (PanelRules.canForward(notification.packageName, packageName, allowed)) {
                container.notificationGateway.emit(NotificationEvent.Removed(notification.key, notification.packageName))
            }
        }
    }

    internal fun activePanelNotifications(): List<PanelNotification> {
        val allowed = container.preferencesCache.value.allowedPackages
        return activeNotifications.orEmpty()
            .asSequence()
            .filter { PanelRules.canForward(it.packageName, packageName, allowed) }
            .filterNot { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
            .mapNotNull(::extract)
            .let { PanelRules.notificationSyncItems(it.toList()) }
    }

    internal fun markReadAndDismiss(key: String): Boolean {
        val sbn = activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        sbn.notification.actions.orEmpty()
            .firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
            ?.let { action ->
                try {
                    action.actionIntent.send()
                } catch (_: PendingIntent.CanceledException) {
                    // La aplicación propietaria retiró la acción; el aviso aún puede cancelarse.
                }
            }
        cancelNotification(key)
        return true
    }

    private fun extract(sbn: StatusBarNotification): PanelNotification? {
        val extras = sbn.notification.extras ?: return null
        @Suppress("DEPRECATION")
        val lastMessage = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle
        @Suppress("DEPRECATION")
        val senderPerson = lastMessage?.getParcelable("sender_person") as? Person
        val title = senderPerson?.name?.toString()
            ?: lastMessage?.getCharSequence("sender")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = lastMessage?.getCharSequence("text")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))
        if (title.isBlank() && text.isBlank()) return null
        val timestamp = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        return PanelNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            app = appName.take(40),
            title = title.take(120),
            text = text.replace(Regex("\\s+"), " ").trim().take(600),
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)),
            postedAt = timestamp,
        )
    }
}
