package com.xiaodou.notify.listener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.ui.MainActivity

/**
 * 通知操作桥接：恢复、推迟、来电式提醒、打开通知、撤销。
 * cancel() 由 NotificationListenerService 实现。
 */
object NotificationBridge {

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** 通过监听器用系统 key 精确消除通知 */
    fun cancel(key: String): Boolean {
        val service = NotificationMonitorService.instance ?: return false
        return try {
            service.cancelNotification(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** FR-H4：把历史通知以本 App 名义重新发布到通知栏 */
    fun showRestored(context: Context, id: Long, title: String, text: String, appName: String) {
        val openPi = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java).putExtra("open_history", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, XiaodouApp.CH_RESTORED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("来源：$appName")
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm(context).notify("restored_$id".hashCode(), n)
    }

    /** 5 秒撤销按钮（FR-E1） */
    fun showUndo(context: Context, id: Long, title: String) {
        val undoPi = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, UndoReceiver::class.java).putExtra("id", id).putExtra("title", title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, XiaodouApp.CH_RESTORED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.undo_notification_title))
            .setContentText(title)
            .addAction(R.drawable.ic_history, context.getString(R.string.undo_action), undoPi)
            .setTimeoutAfter(5000)
            .setAutoCancel(true)
            .build()
        nm(context).notify("undo_$id".hashCode(), n)
    }

    /** FR-E3：验证码一键复制通知（系统不允许给他人通知加按钮时的降级方案） */
    fun showCodeCopy(context: Context, id: Long, code: String) {
        val copyPi = PendingIntent.getBroadcast(
            context, ("code_$id").hashCode(),
            Intent(context, CodeCopyReceiver::class.java).putExtra("code", code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, XiaodouApp.CH_SNOOZED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.code_detected_title))
            .setContentText(context.getString(R.string.code_detected_text, code))
            .addAction(R.drawable.ic_history, context.getString(R.string.code_copy_action), copyPi)
            .setContentIntent(copyPi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        nm(context).notify(("code_$id").hashCode(), n)
    }

    fun showSnoozed(context: Context, id: Long, title: String, text: String) {
        val n = NotificationCompat.Builder(context, XiaodouApp.CH_SNOOZED)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm(context).notify("snoozed_$id".hashCode(), n)
    }

    /** 来电式强提醒（FR-R3-7）：全屏 + 持续响铃，尽力而为 */
    fun showCallStyle(context: Context, title: String, text: String) {
        val fullScreenPi = PendingIntent.getActivity(
            context, 99,
            Intent(context, MainActivity::class.java).putExtra("call_ui", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, XiaodouApp.CH_CALL_UI)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        nm(context).notify("call_ui".hashCode(), n)
    }

    fun openNotification(context: Context, packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) { }
    }
}
