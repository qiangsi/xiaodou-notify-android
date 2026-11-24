package com.xiaodou.notify.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.engine.MuteController

/**
 * 静音到期闹钟接收器（v1.0.11）。
 *
 * 系统按 muteUntil 时刻唤醒本接收器，即使 App 进程已被杀、设备处于 Doze、用户没有重新打开 App。
 * 收到后统一走 [MuteController.recoverIfNeeded]：到期则恢复铃声并清状态，未到期则重挂巡检与闹钟。
 */
class MuteExpireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? XiaodouApp ?: return
        MuteController.recoverIfNeeded(context.applicationContext, app.prefs)
    }
}
