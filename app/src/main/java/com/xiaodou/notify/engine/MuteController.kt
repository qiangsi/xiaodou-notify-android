package com.xiaodou.notify.engine

import android.content.Context
import android.media.AudioManager
import com.xiaodou.notify.util.Prefs

/**
 * 智能静音判定器（v1.7.12 重写）。
 *
 * 【设计原则】本模块绝不改动系统铃声模式（AudioManager.ringerMode）。
 * 旧实现把 ringerMode 设成 SILENT，等于把整台手机静音——所有 App 的通知、闹钟、
 * 媒体声音全被掐掉，这不是「智能」，是一刀切，还会因恢复失败导致永久静音。
 * 现在「静音」的正确语义是：只把判定为骚扰的那条通知从通知栏静默消除
 * （由调用方 cancelNotification），历史照常加密落库，其它通知与系统铃声完全不受影响。
 *
 * 本类只做一件事：按 App 维度的滑动窗口统计高频通知，判断是否该静默消除当前这条。
 * - 原 ringerMode / muteUntil / muteOriginalMode / 闹钟 / MuteExpireReceiver / 巡检
 *   全部废弃（不再有全局静音状态，也就无需恢复、无需闹钟兜底）。
 * - 验证码 / 来电 / 媒体 / 常驻通知一律豁免，永不参与静音判定。
 * - 来电/通话期间不计入窗口，并清空该包窗口，挂断后从零累计。
 * - 触发窗口只在进程内存，重启即从零开始——不再有跨进程残留的「静音状态」。
 */
object MuteController {

    /** 触发窗口清理阈值：1 小时无活动的包直接丢弃 */
    private const val IDLE_PURGE_MS = 3_600_000L

    /** 永不参与静音判定的通知类别：验证码 / 来电 / 媒体 / 常驻 */
    private val EXEMPT_CATEGORIES = setOf("code", "call", "media", "ongoing")

    private val lock = Any()

    /** 包名 -> 滑动窗口内的通知时刻。所有访问都在 lock 内，避免并发通知竞态。 */
    private val recentTimes = mutableMapOf<String, MutableList<Long>>()

    /**
     * 智能静音判定：返回 true 表示「当前这条通知应被静默消除」。
     * 命中后由调用方执行 cancelNotification，本方法不碰系统铃声、不消除通知。
     *
     * @param category 通知类别，用于豁免验证码/来电/媒体/常驻；传 null 时按普通通知处理
     */
    fun shouldSmartMute(context: Context, pkg: String, category: String?, prefs: Prefs): Boolean {
        if (!prefs.smartMuteEnabled) return false
        if (category != null && category in EXEMPT_CATEGORIES) return false
        val am = audioManager(context) ?: return false
        if (isCallActive(am)) {
            synchronized(lock) { recentTimes.remove(pkg) }
            return false
        }

        val now = System.currentTimeMillis()
        val windowMs = prefs.muteWindowSec.coerceIn(5, 3600) * 1000L
        val threshold = prefs.muteThreshold.coerceIn(2, 100)
        var triggered = false
        synchronized(lock) {
            val list = recentTimes.getOrPut(pkg) { mutableListOf() }
            list.add(now)
            list.removeAll { now - it > windowMs }
            if (recentTimes.size > 200) {
                recentTimes.entries.removeAll {
                    it.value.isEmpty() || now - (it.value.maxOrNull() ?: 0L) > IDLE_PURGE_MS
                }
            }
            if (list.size >= threshold) {
                list.clear()
                triggered = true
            }
        }
        return triggered
    }

    /** 兼容旧调用（无类别信息）：按普通通知处理 */
    fun shouldSmartMute(context: Context, pkg: String, prefs: Prefs): Boolean =
        shouldSmartMute(context, pkg, null, prefs)

    /** 清除所有高频窗口（用户手动清空诊断状态时用） */
    fun clearWindows() {
        synchronized(lock) { recentTimes.clear() }
    }

    private fun audioManager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 通过音频模式识别来电，无需 READ_PHONE_STATE 危险权限。 */
    private fun isCallActive(am: AudioManager): Boolean {
        val mode = am.mode
        return mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
