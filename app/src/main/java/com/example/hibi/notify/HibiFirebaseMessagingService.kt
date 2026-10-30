package com.example.hibi.notify

import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class HibiFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New FCM token generated: $token")
        registerTokenToFirestore(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("FCM", "Message received from: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "株の買い時リマインダー"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: "設定されたシナリオの買い場時期になりました"

        val notificationId = System.currentTimeMillis().toInt()
        postStockNotification(
            context = applicationContext,
            notificationId = notificationId,
            title = title,
            body = body,
        )
    }

    companion object {
        /**
         * アプリ起動時などに呼び出して、最新の FCM トークンを Firestore に同期する
         */
        fun syncDeviceToken() {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    Log.d("FCM", "Current FCM token: $token")
                    registerTokenToFirestore(token)
                }
                .addOnFailureListener { e ->
                    Log.w("FCM", "Fetching FCM registration token failed", e)
                }
        }

        private fun registerTokenToFirestore(token: String) {
            val data = mapOf(
                "token" to token,
                "updatedAt" to com.google.firebase.Timestamp.now(),
            )
            Firebase.firestore.collection("fcm_tokens").document(token)
                .set(data, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d("FCM", "Device token registered to Firestore successfully")
                }
                .addOnFailureListener { e ->
                    Log.w("FCM", "Failed to register device token to Firestore", e)
                }
        }
    }
}
