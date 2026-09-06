package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.session.UserSessionManager

class SndmartApp : Application() {

    lateinit var sessionManager: UserSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionManager = UserSessionManager(this)
        createNotificationChannel()
        initFirebaseSafety()
    }

    private fun initFirebaseSafety() {
        try {
            val app = com.google.firebase.FirebaseApp.getInstance()
            val apiKey = app.options.apiKey
            val isFake = apiKey.contains("FakeKey", ignoreCase = true) ||
                         apiKey.contains("placeholder", ignoreCase = true) ||
                         app.options.gcmSenderId == "123456789012"
            if (isFake) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
            }
        } catch (e: Exception) {
            // FirebaseApp not present or not initialized
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_ORDERS,
                "Order Status Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active Sndmart order progress, dispatch, and delivery"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID_ORDERS = "sndmart_order_notifications"
        lateinit var instance: SndmartApp
            private set
    }
}
