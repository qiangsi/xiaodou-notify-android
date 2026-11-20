package com.xiaodou.notify.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.data.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 5 秒撤销按钮点击接收器 */
class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as com.xiaodou.notify.XiaodouApp
                val entity = app.database.notificationDao().getById(id)
                if (entity != null) {
                    NotificationBridge.showRestored(
                        context, entity.id,
                        CryptoHelper.decrypt(entity.titleEnc),
                        CryptoHelper.decrypt(entity.textEnc),
                        entity.appName
                    )
                    app.database.notificationDao().update(entity.copy(result = Result.KEPT, filterReason = "用户撤销恢复"))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
