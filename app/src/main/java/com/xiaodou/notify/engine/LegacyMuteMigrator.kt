package com.xiaodou.notify.engine

import android.content.Context
import android.media.AudioManager
import com.xiaodou.notify.util.Prefs

/**
 * 旧版全局静音遗留状态的一次性迁移器（v1.7.13）。
 *
 * 【为什么需要】
 * v1.7.1~v1.7.11 的旧实现会把整台手机设为静音（AudioManager.ringerMode = SILENT），
 * 并把静音前的铃声档位写进 muteOriginalMode、到期时刻写进 muteUntil。若进程被杀或
 * Doze 冻结，恢复任务丢失，手机会永远停在静音。
 * v1.7.12 起本 App 改为按条静默消除、彻底不再写 ringerMode，但旧版遗留的静音状态和
 * 标记没有清理，导致从旧版升级上来的用户手机一直静音、且找不到恢复入口。
 *
 * 【边界铁律】
 * 这不是「静音功能」，而是对历史遗留状态的一次性收尾，与日常通知过滤完全隔离：
 *  1) 全生命周期只执行一次（legacyMuteRecovered 置位后永不重入）；
 *  2) 只有检测到旧版静音标记（muteUntil > 0）才可能触发；
 *  3) 只有当前手机确实还停在静音/振动档才恢复；用户已手动调回响铃则绝不改动；
 *  4) 恢复目标为静音前档位；若档位无效（SILENT 或未设置）兜底恢复 NORMAL；
 *  5) 执行完立即清除全部旧标记，之后本 App 永不再写 ringerMode。
 */
object LegacyMuteMigrator {

    fun migrateIfNeeded(context: Context, prefs: Prefs) {
        if (prefs.legacyMuteRecovered) return
        try {
            val hadLegacyMute = prefs.muteUntil > 0L || prefs.muteOriginalMode >= 0
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (hadLegacyMute && am != null && isSilent(am.ringerMode)) {
                // 静音前的档位；0(SILENT) 或非法值一律兜底为 NORMAL，避免「恢复成静音」
                val original = prefs.muteOriginalMode
                val target = when (original) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_NORMAL
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                try { am.ringerMode = target } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            // 迁移失败也不能影响 App 启动；标记仍置位，避免每次开机反复尝试
        } finally {
            clearLegacyFlags(prefs)
            prefs.legacyMuteRecovered = true
        }
    }

    /**
     * 设置页「恢复手机铃声」按钮：供用户手动把被旧版静音的手机调回响铃。
     * 仅当当前确实处于静音/振动档时才改动；返回实际设置到的档位，无需改动时返回 null。
     * 与启动迁移不同，这是用户显式触发的应急操作，不受 legacyMuteRecovered 限制。
     */
    fun restoreRingerManually(context: Context, prefs: Prefs): Int? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        if (!isSilent(am.ringerMode)) return null
        val original = prefs.muteOriginalMode
        val target = when (original) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return try {
            am.ringerMode = target
            clearLegacyFlags(prefs)
            target
        } catch (e: Exception) {
            null
        }
    }

    private fun isSilent(mode: Int): Boolean =
        mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE

    private fun clearLegacyFlags(prefs: Prefs) {
        prefs.muteUntil = 0L
        prefs.muteOriginalMode = -1
        prefs.muteSource = ""
    }
}
