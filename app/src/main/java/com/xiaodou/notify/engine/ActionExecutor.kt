package com.xiaodou.notify.engine

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.listener.NotificationBridge
import com.xiaodou.notify.util.Prefs
import kotlinx.coroutines.CoroutineScope
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
            ActionOutcome(true, Result.RULE, "已推迟")
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
        val seconds = param.toIntOrNull()?.coerceIn(1, 60) ?: 5
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        val old = am.ringerMode
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
        handler.postDelayed({ am.ringerMode = old }, TimeUnit.SECONDS.toMillis(seconds.toLong()))
    }

    private fun snooze(context: Context, entity: NotificationEntity, param: String, title: String, text: String) {
        val minutes = param.toIntOrNull()?.coerceIn(1, 1440) ?: 10
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
    private val recentTimes = mutableMapOf<String, MutableList<Long>>()

    fun shouldSmartMute(context: Context, pkg: String, prefs: Prefs): Boolean {
        if (!prefs.smartMuteEnabled) return false
        val now = System.currentTimeMillis()
        val windowMs = prefs.muteWindowSec.coerceIn(5, 3600) * 1000L
        val threshold = prefs.muteThreshold.coerceIn(2, 100)
        val list = recentTimes.getOrPut(pkg) { mutableListOf() }
        list.add(now)
        list.removeAll { now - it > windowMs }
        // 防止 map 无限增长：清理超过 1 小时无活动的包
        if (recentTimes.size > 200) {
            recentTimes.entries.removeAll { it.value.isEmpty() || now - (it.value.maxOrNull() ?: 0L) > 3_600_000 }
        }
        if (list.size >= threshold) {
            muteBriefly(context, prefs.muteDurationSec.coerceIn(5, 600).toString())
            list.clear()
            return true
        }
        return false
    }

    /** 用户手动提前解除全局静音（FR-E2） */
    fun unmute(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }
}
