package com.example.core.comms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.notifications.AndroidNotificationHelper

/**
 * Foreground service keeping microphone, camera, and optional MediaProjection screen sharing
 * alive during active 1-to-1 calls or Family Meet group meetings on Android 10–15+.
 */
class ActiveCallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Family Call Active"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: "Tap to return to active audio/video session"
        val includeCamera = intent?.getBooleanExtra(EXTRA_INCLUDE_CAMERA, false) ?: false
        val includeProjection = intent?.getBooleanExtra(EXTRA_INCLUDE_PROJECTION, false) ?: false

        ensureChannel()
        val notification = buildNotification(title, subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceTypes = 0
            val hasMicPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (hasMicPerm) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (includeCamera && hasCamPerm) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            if (includeProjection) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            if (serviceTypes != 0) {
                runCatching {
                    startForeground(NOTIFICATION_ID, notification, serviceTypes)
                }.onFailure {
                    // Fallback to minimal valid foreground notification without prohibited types
                    runCatching { startForeground(NOTIFICATION_ID, notification) }
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ACTIVE_CALL,
            "Active Calls & Meetings",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing audio/video calls and Family Meet sessions"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AndroidNotificationHelper.EXTRA_TARGET_MODULE, "FAMILY_MEET")
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_notification_finance)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingOpen)
            .build()
    }

    companion object {
        const val CHANNEL_ACTIVE_CALL = "active_calls"
        const val NOTIFICATION_ID = 4401
        private const val ACTION_START_OR_UPDATE = "com.example.core.comms.START_CALL_SERVICE"
        private const val ACTION_STOP = "com.example.core.comms.STOP_CALL_SERVICE"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_INCLUDE_CAMERA = "extra_include_camera"
        private const val EXTRA_INCLUDE_PROJECTION = "extra_include_projection"

        fun startOrUpdate(
            context: Context,
            title: String,
            subtitle: String,
            includeCamera: Boolean,
            includeMediaProjection: Boolean
        ) {
            val intent = Intent(context, ActiveCallForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_INCLUDE_CAMERA, includeCamera)
                putExtra(EXTRA_INCLUDE_PROJECTION, includeMediaProjection)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ActiveCallForegroundService::class.java))
            }
        }
    }
}
