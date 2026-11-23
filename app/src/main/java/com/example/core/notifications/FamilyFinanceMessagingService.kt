package com.example.core.notifications

import android.util.Log
import com.example.core.firebase.FirebaseServiceRegistry
import com.example.core.local.ThemePreferencesStore
import com.example.core.model.FcmTokenDoc
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FamilyFinanceMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val appContext = applicationContext
        serviceScope.launch {
            runCatching {
                val prefs = ThemePreferencesStore(appContext)
                prefs.setCachedFcmToken(token)
                val uid = FirebaseServiceRegistry.auth.currentUser?.uid ?: return@runCatching
                val deviceId = prefs.getOrCreateDeviceId()
                val enabled = prefs.isNotificationsEnabled()
                val db = FirebaseServiceRegistry.firestore(appContext)
                val payload = FcmTokenDoc(
                    deviceId = deviceId,
                    token = token,
                    platform = "ANDROID",
                    notificationsEnabled = enabled
                ).toUpsertMap()
                db.collection("users")
                    .document(uid)
                    .collection("fcm_tokens")
                    .document(deviceId)
                    .set(payload)
                    .await()
            }.onFailure { err ->
                Log.w("FFMessagingService", "Unable to sync refreshed FCM token: ${err.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val appContext = applicationContext
        val data = remoteMessage.data
        val title = remoteMessage.notification?.title?.takeIf { it.isNotBlank() }
            ?: data["title"]?.takeIf { it.isNotBlank() }
            ?: "Family Finance"
        val body = remoteMessage.notification?.body?.takeIf { it.isNotBlank() }
            ?: data["body"]?.takeIf { it.isNotBlank() }
            ?: return
        val type = data["notificationType"] ?: data["type"] ?: "GENERAL"
        val deepLinkModule = data["deepLinkModule"] ?: data["module"]
        val relatedRecordId = data["relatedRecordId"]
        val notifId = data["notificationId"]
            ?: remoteMessage.messageId
            ?: "fcm_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        serviceScope.launch {
            runCatching {
                val enabled = ThemePreferencesStore(appContext).isNotificationsEnabled()
                if (enabled) {
                    AndroidNotificationHelper(appContext).postNotification(
                        notificationId = notifId,
                        title = title,
                        body = body,
                        notificationType = type,
                        deepLinkModule = deepLinkModule,
                        relatedRecordId = relatedRecordId
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
