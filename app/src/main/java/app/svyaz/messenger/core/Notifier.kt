package app.svyaz.messenger.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.svyaz.messenger.R
import app.svyaz.messenger.ui.MainActivity

class Notifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NODE, "Работа узла", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun openApp(peer: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (peer != null) putExtra(MainActivity.EXTRA_PEER, peer)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun nodeNotification(text: String) = NotificationCompat.Builder(context, CHANNEL_NODE)
        .setSmallIcon(R.drawable.ic_mark)
        .setContentTitle("Узел работает")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openApp(null, 0))
        .build()

    fun updateNode(text: String) {
        if (hasPermission()) NotificationManagerCompat.from(context).notify(ID_NODE, nodeNotification(text))
    }

    fun showMessage(peer: String, text: String, names: Map<String, String>) {
        if (!hasPermission()) return
        val requestCode = peer.hashCode()
        // On the lock screen only the fact of a message is shown, never its text.
        val public = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle("Новое сообщение")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(displayName(peer, names))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setGroup(GROUP_MESSAGES)
            .setContentIntent(openApp(peer, requestCode))
            .build()
        NotificationManagerCompat.from(context).notify(peer, ID_MESSAGE, notification)
    }

    fun clear(peer: String) {
        NotificationManagerCompat.from(context).cancel(peer, ID_MESSAGE)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ID_NODE = 1
        private const val ID_MESSAGE = 2
        private const val CHANNEL_MESSAGES = "messages"
        private const val CHANNEL_NODE = "node"
        private const val GROUP_MESSAGES = "app.svyaz.messenger.MESSAGES"
    }
}
