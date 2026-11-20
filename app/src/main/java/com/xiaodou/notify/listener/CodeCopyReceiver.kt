package com.xiaodou.notify.listener

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.xiaodou.notify.R

/** FR-E3：验证码一键复制到剪贴板 */
class CodeCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("验证码", code))
        Toast.makeText(context, context.getString(R.string.code_copied_toast), Toast.LENGTH_SHORT).show()
    }
}
