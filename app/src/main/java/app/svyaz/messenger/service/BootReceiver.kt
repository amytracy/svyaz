package app.svyaz.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.svyaz.messenger.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = app.repository.settings.current()
                if (settings.onboarded && settings.background && settings.autostart) {
                    ContextCompat.startForegroundService(context, Intent(context, NodeService::class.java))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
