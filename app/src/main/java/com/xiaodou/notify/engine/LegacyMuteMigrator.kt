package com.xiaodou.notify.engine

import android.content.Context
import android.media.AudioManager
import com.xiaodou.notify.util.Prefs

/**
 * 旧版全局静音遗留状态的一次性收尾器（v1.7.14 加固）。
 *
 * 【背景】
 * v1.7.1~v1.7.11 会把整台手机设为静音（ringerMode = SILENT），到期再恢复。
 * 进程被杀 / Doze 冻结时恢复任务丢失，手机永久停在静音。
 * v1.7.12 起改为按条静默消除、不再写铃声，但遗留的静音没人收拾。
 *
 * 【v1.7.13 的漏洞（本版修复）】
 * v1.7.13 用「旧标记 muteUntil>0 || muteOriginalMode>=0」判断是否恢复。
 * 但旧版在静音「到期」时会先清标记再恢复铃声；若那次恢复失败，标记已被清空、
 * 手机却还静音——此时 hadLegacyMute=false，迁移器不恢复，还把 legacyMuteRecovered
 * 置位导致永不重试。这正是用户反馈「一直静音、调不回来」的根因。
 *
 * 【v1.7.14 新策略】
 * 启动迁移不再依赖易失的旧标记。只要 legacyMuteRecovered 尚未置位
 * （即本 App 首次运行本套逻辑，涵盖所有从旧版升级上来的用户），
 * 且手机当前处于静音/振动——就无条件恢复为响铃（NORMAL）并把铃声音量抬到可听值。
 * 恢复后清标记并置位 legacyMuteRecovered，之后本 App 永不再碰系统铃声：
 * 用户日后自己调静音不会被改动。
 *
 * 【边界铁律】
 * 这不是「静音功能」，而是对历史遗留状态的一次性收尾，与日常通知过滤完全隔离。
 * 除本迁移器和用户显式点击「恢复手机铃声」外，全工程零 ringerMode / 音量写入。
 */
object LegacyMuteMigrator {

    private const val MIN_AUDIBLE_VOLUME = 3 // 保证铃声可听的最低档（0=静音）

    /**
     * App 启动时调用一次。首次运行（未置位 legacyMuteRecovered）时，
     * 若手机处于静音/振动或铃声音量为 0，恢复为响铃 + 可听音量，随后永久置位。
     */
    fun migrateIfNeeded(context: Context, prefs: Prefs) {
        if (prefs.legacyMuteRecovered) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && needsRestore(am)) {
                forceRestore(am)
            }
        } catch (e: Exception) {
            // 迁移失败不影响 App 启动
        } finally {
            // 无论成功与否都置位：只尝试一次，绝不反复干预用户的铃声设置
            clearLegacyFlags(prefs)
            prefs.legacyMuteRecovered = true
        }
    }

    /**
     * 设置页「恢复手机铃声」按钮：用户显式触发的应急恢复。
     * 不受 legacyMuteRecovered 限制——任何时候点了都能把手机调回可听响铃。
     * @return true=执行了恢复；false=当前已是响铃且音量足够，无需改动
     */
    fun restoreRingerManually(context: Context, prefs: Prefs): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (!needsRestore(am)) return false
        forceRestore(am)
        clearLegacyFlags(prefs)
        return true
    }

    /** 是否处于「对用户而言像静音」的状态：静音/振动档，或铃声音量过低 */
    private fun needsRestore(am: AudioManager): Boolean {
        val modeSilent = am.ringerMode == AudioManager.RINGER_MODE_SILENT ||
            am.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        val ringLow = try { am.getStreamVolume(AudioManager.STREAM_RING) < MIN_AUDIBLE_VOLUME } catch (e: Exception) { false }
        return modeSilent || ringLow
    }

    /** 无条件恢复为响铃档，并把铃声/通知音量抬到可听值 */
    private fun forceRestore(am: AudioManager) {
        try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { }
        bumpVolume(am, AudioManager.STREAM_RING)
        bumpVolume(am, AudioManager.STREAM_NOTIFICATION)
    }

    private fun bumpVolume(am: AudioManager, stream: Int) {
        try {
            val max = am.getStreamMaxVolume(stream)
            if (am.getStreamVolume(stream) < MIN_AUDIBLE_VOLUME) {
                val target = if (max >= MIN_AUDIBLE_VOLUME) {
                    // 取最大音量的约 60%，保证可听又不至于过吵
                    (max * 0.6f).toInt().coerceAtLeast(MIN_AUDIBLE_VOLUME)
                } else MIN_AUDIBLE_VOLUME
                am.setStreamVolume(stream, target, 0)
            }
        } catch (e: Exception) { }
    }

    private fun clearLegacyFlags(prefs: Prefs) {
        prefs.muteUntil = 0L
        prefs.muteOriginalMode = -1
        prefs.muteSource = ""
    }
}
