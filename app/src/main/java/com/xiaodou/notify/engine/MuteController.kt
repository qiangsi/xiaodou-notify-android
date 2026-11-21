package com.xiaodou.notify.engine

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.xiaodou.notify.util.Prefs

/**
 * 智能静音 / 规则静音的统一状态机（v1.7.1 修复）。
 *
 * 旧实现的问题：
 *  1. muteBriefly() 每次都把「当前 ringerMode」当作原模式。高频通知会在上一次静音未到期时
 *     再次触发，此时当前模式已是 SILENT，于是延迟任务被写成「恢复到 SILENT」——之后没有任何
 *     任务再恢复，手机永久静音。
 *  2. unmute() 只写 NORMAL，不撤销挂起的延迟任务，任务到期后把旧值写回，静音复活；
 *     同时把用户原本的振动档错误地改成 NORMAL。
 *  3. 恢复逻辑只在内存 Handler 里，进程被杀即丢失，同样导致永久静音。
 *  4. 没有来电/通话判断，来电时仍在静音，导致漏接重要电话。
 *  5. 触发窗口 mutableMap 非线程安全，并发通知下存在竞态。
 *
 * 本实现的约束：
 *  - 原模式只在首次静音前记录一次；重复触发只延长截止时间，绝不重读已静音的模式。
 *  - 状态（原模式 + 到期时刻）持久化到 Prefs，进程重启/回前台后重新对齐。
 *  - 手动解除、来电、超时共用同一个 cancel()，先撤销挂起任务再恢复。
 *  - 静音期间巡检 AudioManager.mode，来电立即恢复铃声并清空触发窗口（无需危险权限）。
 *  - 用户若自行通过音量键/快捷设置改了铃声，认定其已接管，撤销状态且不覆盖其选择。
 */
object MuteController {

    /** Prefs 中「未静音」的哨兵值 */
    private const val NO_ORIGINAL = -1
    /** 静音期间巡检间隔：来电后尽快恢复，控制在 0.5 秒内 */
    private const val WATCH_INTERVAL_MS = 500L
    /** 触发窗口清理阈值：1 小时无活动的包直接丢弃 */
    private const val IDLE_PURGE_MS = 3_600_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 包名 -> 滑动窗口内的通知时刻。所有访问都在 lock 内，避免并发通知竞态。 */
    private val recentTimes = mutableMapOf<String, MutableList<Long>>()
    private var watchTask: Runnable? = null

    /**
     * 智能静音判定。返回 true 表示本次触发了静音。
     * 来电/通话中既不计入窗口也不静音，并清空该包的窗口，挂断后从零开始累计。
     */
    fun shouldSmartMute(context: Context, pkg: String, prefs: Prefs): Boolean {
        if (!prefs.smartMuteEnabled) return false
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
        if (triggered) mute(context, prefs.muteDurationSec, prefs, "smart")
        return triggered
    }

    /**
     * 规则动作 MUTE 与智能静音共用的唯一静音入口。
     * 已在静音窗口内时只续期，不重新读取 ringerMode（它此刻就是 SILENT）。
     */
    fun mute(context: Context, seconds: Int, prefs: Prefs, source: String) {
        val am = audioManager(context) ?: return
        val now = System.currentTimeMillis()
        val durationMs = seconds.coerceIn(1, 600) * 1000L
        synchronized(lock) {
            if (isCallActive(am)) return
            val until = prefs.muteUntil
            val original = prefs.muteOriginalMode
            if (until > now) {
                // 防御：若持久化状态只有截止时间、缺原模式（极端掉电场景），按 NORMAL 兜底，
                // 绝不能在此处重读已被静音的 ringerMode。
                if (original == NO_ORIGINAL) prefs.muteOriginalMode = AudioManager.RINGER_MODE_NORMAL
                prefs.muteUntil = now + durationMs
            } else {
                @Suppress("DEPRECATION")
                prefs.muteOriginalMode = am.ringerMode
                prefs.muteUntil = now + durationMs
                @Suppress("DEPRECATION")
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            prefs.muteSource = source
            scheduleWatch(context.applicationContext, prefs)
        }
    }

    /** 用户手动解除：恢复静音前的模式（可能原本就是振动），不强制切成 NORMAL。 */
    fun unmute(context: Context, prefs: Prefs) = cancel(context, prefs, "manual")

    /**
     * 进程启动 / 回到前台 / 服务重建 / 切后台时调用，把内存状态与持久化状态对齐。
     * 进程被杀后挂起的 Runnable 已丢失，这里按到期时刻决定「续挂巡检」还是「立即恢复」。
     */
    fun recoverIfNeeded(context: Context, prefs: Prefs) {
        if (prefs.muteUntil <= 0L) return
        synchronized(lock) {
            if (prefs.muteUntil <= System.currentTimeMillis()) {
                cancelLocked(context.applicationContext, prefs, "expired")
            } else {
                scheduleWatch(context.applicationContext, prefs)
            }
        }
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

    private fun scheduleWatch(context: Context, prefs: Prefs) {
        clearWatchLocked()
        val task = object : Runnable {
            override fun run() = tick(context, prefs, this)
        }
        watchTask = task
        handler.postDelayed(task, WATCH_INTERVAL_MS)
    }

    private fun tick(context: Context, prefs: Prefs, self: Runnable) {
        val am = audioManager(context) ?: return
        synchronized(lock) {
            if (prefs.muteUntil <= 0L) {
                clearWatchLocked()
                return
            }
            when {
                isCallActive(am) -> {
                    // 来电：立即结束静音并清空触发窗口，挂断后不会立刻再次静音
                    recentTimes.clear()
                    cancelLocked(context, prefs, "call")
                }
                System.currentTimeMillis() >= prefs.muteUntil -> {
                    cancelLocked(context, prefs, "expired")
                }
                @Suppress("DEPRECATION") am.ringerMode != AudioManager.RINGER_MODE_SILENT -> {
                    // 用户自己改了铃声（音量键/快捷设置）：认定已接管，撤销状态且不覆盖其选择
                    clearStateLocked(prefs)
                    clearWatchLocked()
                }
                else -> handler.postDelayed(self, WATCH_INTERVAL_MS)
            }
        }
    }

    private fun cancel(context: Context, prefs: Prefs, reason: String) {
        synchronized(lock) { cancelLocked(context.applicationContext, prefs, reason) }
    }

    private fun cancelLocked(context: Context, prefs: Prefs, reason: String) {
        clearWatchLocked()
        val original = prefs.muteOriginalMode
        clearStateLocked(prefs)
        if (original == NO_ORIGINAL) return
        val am = audioManager(context) ?: return
        @Suppress("DEPRECATION")
        val current = am.ringerMode
        // 来电必须无条件恢复；其余场景若用户已自行改铃声，不再覆盖
        if (reason == "call" || current == AudioManager.RINGER_MODE_SILENT) {
            @Suppress("DEPRECATION")
            am.ringerMode = original
        }
    }

    private fun clearWatchLocked() {
        watchTask?.let { handler.removeCallbacks(it) }
        watchTask = null
    }

    private fun clearStateLocked(prefs: Prefs) {
        prefs.muteUntil = 0L
        prefs.muteOriginalMode = NO_ORIGINAL
        prefs.muteSource = ""
    }
}
