package com.example.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class AndroidNotificationHelper(private val context: Context) {

    fun ensureChannelsCreated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val chatChannel = NotificationChannel(
            CHANNEL_CHAT,
            "Family Chat",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Real-time family group chat messages"
        }

        val financialChannel = NotificationChannel(
            CHANNEL_FINANCIAL,
            "Financial Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Loan due dates, budget alerts, and savings goal milestones"
        }

        val familyChannel = NotificationChannel(
            CHANNEL_FAMILY,
            "Family Activity",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "New family transactions, invitations, and membership approvals"
        }

        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Calls & Meetings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming audio/video calls, missed calls, and Family Meet invitations"
        }

        manager.createNotificationChannels(listOf(chatChannel, financialChannel, familyChannel, callsChannel))
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun channelForType(notificationType: String): String {
        return when (notificationType.trim().uppercase()) {
            "NEW_MESSAGE" -> CHANNEL_CHAT
            "CALL_INCOMING", "CALL_MISSED", "MEETING_INVITE", "MEETING_STARTED" -> CHANNEL_CALLS
            "LOAN_DUE_DATE", "BUDGET_ALERT", "SAVINGS_MILESTONE",
            "PROPOSAL_SUBMITTED", "PROPOSAL_VOTE_CAST", "PROPOSAL_APPROVED",
            "PROPOSAL_REJECTED", "PROPOSAL_NEAR_DEADLINE", "PROPOSAL_EXPIRED",
            "PROPOSAL_EXECUTED" -> CHANNEL_FINANCIAL
            else -> CHANNEL_FAMILY
        }
    }

    fun postNotification(
        notificationId: String,
        title: String,
        body: String,
        notificationType: String,
        deepLinkModule: String? = null,
        relatedRecordId: String? = null
    ) {
        if (!hasNotificationPermission()) return
        ensureChannelsCreated()

        val targetIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            if (!deepLinkModule.isNullOrBlank()) {
                putExtra(EXTRA_TARGET_MODULE, deepLinkModule)
            }
            if (!relatedRecordId.isNullOrBlank()) {
                putExtra(EXTRA_TARGET_RECORD_ID, relatedRecordId)
            }
        }
        val requestCode = notificationId.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = channelForType(notificationType)
        val priority = if (channelId == CHANNEL_CHAT || channelId == CHANNEL_CALLS) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_finance)
            .setContentTitle(title.take(100))
            .setContentText(body.take(400))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(400)))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (notificationType.equals("CALL_INCOMING", ignoreCase = true)) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
        }

        val notification = builder.build()

        runCatching {
            val nm = NotificationManagerCompat.from(context)
            if (nm.areNotificationsEnabled()) {
                nm.notify(requestCode, notification)
            }
        }
    }

    companion object {
        const val CHANNEL_CHAT = "chat_messages"
        const val CHANNEL_CALLS = "calls_and_meetings"
        const val CHANNEL_FINANCIAL = "financial_alerts"
        const val CHANNEL_FAMILY = "family_activity"

        const val EXTRA_TARGET_MODULE = "extra_target_module"
        const val EXTRA_TARGET_RECORD_ID = "extra_target_record_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
