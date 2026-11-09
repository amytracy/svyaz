package app.svyaz.messenger.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.svyaz.messenger.App
import app.svyaz.messenger.core.Notifier
import app.svyaz.messenger.core.reachabilityLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the node alive with the UI closed: there is no server or push to wake the app. */
class NodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as App

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = app.notifier.nodeNotification("Подключаемся…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ID_NODE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_NODE, notification)
        }
        scope.launch {
            app.repository.status
                .map { s -> s?.let { "${reachabilityLabel(it.reachability)} · соединений: ${it.connections.size} · в очереди: ${it.queued}" } }
                .distinctUntilChanged()
                .collect { text -> if (text != null) app.notifier.updateNode(text) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { app.repository.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        val repository = app.repository
        // With background work switched off the visible UI keeps the node running itself.
        if (!repository.uiVisible) CoroutineScope(Dispatchers.Default).launch { repository.stop() }
        super.onDestroy()
    }
}
