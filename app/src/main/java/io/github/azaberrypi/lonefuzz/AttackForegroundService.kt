package io.github.azaberrypi.lonefuzz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AttackForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "攻撃実行", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // 停止は BroadcastReceiver 経由（Activity の再生成に依存しない）。
        // 通知タップ＝停止、明示アクションも停止、別アクションでアプリを開く。
        val stopIntent = Intent(this, StopBurstReceiver::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoneFuzz BURST 実行中")
            .setContentText("タップで停止")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(stopPending)
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .addAction(android.R.drawable.ic_menu_view, "開く", openPending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.azaberrypi.lonefuzz.STOP_BURST"
        const val CHANNEL_ID = "attack_channel"
        const val NOTIFICATION_ID = 1
    }
}
