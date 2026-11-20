package com.xiaodou.notify.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.xiaodou.notify.listener.NotificationMonitorService

/** 判断「通知使用权」是否已授予 */
fun isListenerEnabled(context: Context): Boolean {
    val flat = try {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    } catch (e: Exception) { null } ?: return false
    val cn = ComponentName(context, NotificationMonitorService::class.java)
    return flat.split(":").any {
        ComponentName.unflattenFromString(it) == cn
    }
}

fun openListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

fun hasPostNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
