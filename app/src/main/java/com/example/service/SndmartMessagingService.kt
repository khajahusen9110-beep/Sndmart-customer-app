package com.example.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SndmartApp
import com.example.data.repository.SndmartRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class InAppNotification(
    val title: String,
    val body: String,
    val orderId: String?
)

class SndmartMessagingService : FirebaseMessagingService() {

    private val TAG = "SndmartFCM"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM token: $token")
        val app = application as? SndmartApp ?: return
        val userId = app.sessionManager.userId.value
        if (!userId.isNullOrBlank()) {
            val repo = SndmartRepository(sessionManager = app.sessionManager)
            CoroutineScope(Dispatchers.IO).launch {
                repo.registerDeviceToken(userId, token)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Received message: from=${remoteMessage.from}, data=${remoteMessage.data}")

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Order Update"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: "Your Sndmart order status has changed."
        val orderId = remoteMessage.data["order_id"] ?: remoteMessage.data["id"]

        // Broadcast to in-app foreground banner
        _inAppEvents.tryEmit(InAppNotification(title, body, orderId))

        // Also post system status bar notification
        showSystemNotification(title, body, orderId)
    }

    private fun showSystemNotification(title: String, body: String, orderId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (orderId != null) {
                data = Uri.parse("sndmart://order/$orderId")
                putExtra("order_id", orderId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SndmartApp.CHANNEL_ID_ORDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((orderId?.hashCode() ?: System.currentTimeMillis().toInt()), notification)
    }

    companion object {
        private val _inAppEvents = MutableSharedFlow<InAppNotification>(extraBufferCapacity = 5)
        val inAppEvents: SharedFlow<InAppNotification> = _inAppEvents.asSharedFlow()
    }
}
