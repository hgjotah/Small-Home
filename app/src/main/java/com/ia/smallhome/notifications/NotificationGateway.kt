package com.ia.smallhome.notifications

import com.ia.smallhome.model.NotificationEvent
import com.ia.smallhome.model.PanelNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference

class NotificationGateway {
    private var listener = WeakReference<SmartPanelNotificationListener>(null)
    private val _events = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NotificationEvent> = _events.asSharedFlow()

    internal fun attach(service: SmartPanelNotificationListener) {
        listener = WeakReference(service)
    }

    internal fun detach(service: SmartPanelNotificationListener) {
        if (listener.get() === service) listener.clear()
    }

    internal fun emit(event: NotificationEvent) {
        _events.tryEmit(event)
    }

    fun activeNotifications(): List<PanelNotification> = listener.get()?.activePanelNotifications().orEmpty()

    fun dismiss(key: String): Boolean = listener.get()?.markReadAndDismiss(key) ?: false
}
