package com.xiaodou.notify.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.listener.NotificationBridge
import com.xiaodou.notify.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 规则动作执行结果 */
data class ActionOutcome(
    val applied: Boolean,
    val result: String,
    val reason: String
)

/** 执行规则的各项动作。10 种动作中系统可实现的均已落地，受限动作有降级说明。 */
object ActionExecutor {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    fun execute(
        context: Context,
        entity: NotificationEntity,
        action: String,
        param: String,
        decryptedText: String,
        decryptedTitle: String
    ): ActionOutcome = when (action) {
        Actions.KEEP -> ActionOutcome(false, Result.KEPT, "规则保留")
        Actions.DISMISS -> {
            // 修复 P0：cancelNotification 需要系统 StatusBarNotification.key，
            // 不能用数据库 id。老数据无 sysKey 时诚实降级，不伪装成功。
            val key = entity.sysKey
            if (key.isNullOrEmpty()) {
                ActionOutcome(false, Result.KEPT, "无法消除：缺少系统通知标识（旧版本记录）")
            } else {
                NotificationBridge.cancel(key)
                ActionOutcome(true, Result.RULE, "已消除")
            }
        }
        Actions.MUTE -> {
            muteBriefly(context, param)
            ActionOutcome(true, Result.RULE, "已静音")
        }
        Actions.SNOOZE -> {
            snooze(context, entity, param, decryptedTitle, decryptedText)
            ActionOutcome(true, Result.RULE, "已推迟并加入待办")
        }
        // 系统不允许第三方 App 改写他人通知内容/提示音，诚实降级不伪装成功（FR-R3）
        Actions.REPLACE -> ActionOutcome(false, Result.KEPT, "替换内容受系统限制未执行；可在历史中查看原文")
        Actions.RINGTONE -> ActionOutcome(false, Result.KEPT, "更改铃声受系统限制未执行")
        Actions.TTS -> {
            speak(context, decryptedTitle, decryptedText)
            ActionOutcome(true, Result.RULE, "已朗读")
        }
        Actions.CALL_UI -> {
            NotificationBridge.showCallStyle(context, decryptedTitle, decryptedText)
            ActionOutcome(true, Result.RULE, "来电式提醒")
        }
        Actions.TAP_BUTTON -> ActionOutcome(false, Result.KEPT, "点击按钮需系统辅助权限，未启用")
        Actions.OPEN -> {
            NotificationBridge.openNotification(context, entity.packageName)
            ActionOutcome(true, Result.RULE, "已打开通知")
        }
        Actions.WEBHOOK -> {
            val ok = WebhookSender.send(param, decryptedTitle, decryptedText, entity)
            ActionOutcome(ok, Result.KEPT, if (ok) "已转发" else "转发失败")
        }
        else -> ActionOutcome(false, Result.KEPT, "未知动作")
    }

    private fun muteBriefly(context: Context, param: String) {
        val seconds = param.toIntOrNull()?.coerceIn(1, 600) ?: 5
        val prefs = (context.applicationContext as? XiaodouApp)?.prefs ?: return
        // v1.7.1：统一交给 MuteController，避免「重复触发把 SILENT 当原模式」导致永久静音
        MuteController.mute(context, seconds, prefs, "rule")
    }

    private fun snooze(context: Context, entity: NotificationEntity, param: String, title: String, text: String) {
        val minutes = param.toIntOrNull()?.coerceIn(1, 1440) ?: 10
        // v1.7.5：推迟的通知自动进待办收件箱，避免「弹完就忘」
        (context.applicationContext as? XiaodouApp)?.prefs?.addTodo(entity.id)
        handler.postDelayed({
            NotificationBridge.showSnoozed(context, entity.id, title, text)
        }, TimeUnit.MINUTES.toMillis(minutes.toLong()))
    }

    private fun speak(context: Context, title: String, text: String) {
        val app = context.applicationContext as? XiaodouApp ?: return
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.CHINA)
                    tts?.speak("$title，$text", TextToSpeech.QUEUE_FLUSH, null, "xiaodou-tts")
                }
            }
        } else {
            tts?.speak("$title，$text", TextToSpeech.QUEUE_FLUSH, null, "xiaodou-tts")
        }
        app.appScope.launch(Dispatchers.Main) { /* 保持引用 */ }
    }

    /** 智能静音：短时间内同 App 高频通知时临时静音（阈值/窗口/时长均可配 FR-E2） */
    fun shouldSmartMute(context: Context, pkg: String, prefs: Prefs): Boolean =
        MuteController.shouldSmartMute(context, pkg, prefs)

    /** 用户手动提前解除全局静音（FR-E2）：撤销挂起任务并恢复到静音前的模式 */
    fun unmute(context: Context, prefs: Prefs) = MuteController.unmute(context, prefs)
}
