package com.xiaodou.notify.listener

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.ui.MainActivity

/** 前台保活服务：降低被系统杀后台的概率（PRD 非功能需求）。 */
class KeepAliveService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification())
        KeepAliveJobService.schedule(applicationContext)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openApp, piFlags)

        return NotificationCompat.Builder(this, XiaodouApp.CH_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_service_label))
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** 从最近任务划掉时立即安排看门狗恢复 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        KeepAliveJobService.schedule(applicationContext)
        try {
            start(applicationContext)
        } catch (e: Exception) {
            // Android 12+ 可能禁止后台启动前台服务，交给看门狗下一周期处理
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        KeepAliveJobService.schedule(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            context.startForegroundService(i)
        }
    }
}
