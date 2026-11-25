package com.xiaodou.notify.engine

import android.content.Context
import com.xiaodou.notify.util.Prefs
import org.json.JSONObject

/**
 * v1.7.5 情景模式：把「暂停过滤 / 智能静音 / 排除应用 / 静音档位」打包成
 * 专注·睡眠·通勤三个一键场景。进入前把相关开关快照到 Prefs，退出时精确还原，
 * 避免覆盖用户原有设置。
 *
 * 纯配置层，不碰 Room 表结构；小豆版由 build_xiaodou.py 自动重构包名。
 */
object SceneController {

    const val OFF = 0
    const val FOCUS = 1
    const val SLEEP = 2
    const val COMMUTE = 3

    fun name(context: Context, mode: Int): String = when (mode) {
        FOCUS -> label(context, "scene_focus", "专注")
        SLEEP -> label(context, "scene_sleep", "睡眠")
        COMMUTE -> label(context, "scene_commute", "通勤")
        else -> label(context, "scene_off", "常规")
    }

    private fun label(context: Context, resName: String, fallback: String): String {
        val id = context.resources.getIdentifier(resName, "string", context.packageName)
        return if (id != 0) context.getString(id) else fallback
    }

    /** 循环切换：常规 → 专注 → 睡眠 → 通勤 → 常规 */
    fun next(context: Context, prefs: Prefs): Int {
        val next = when (prefs.sceneMode) {
            OFF -> FOCUS
            FOCUS -> SLEEP
            SLEEP -> COMMUTE
            else -> OFF
        }
        apply(context, prefs, next)
        return next
    }

    fun apply(context: Context, prefs: Prefs, mode: Int) {
        if (mode == OFF) {
            restore(prefs)
            return
        }
        // 首次进入场景时备份；场景间直接切换时保留最初的备份
        if (prefs.sceneMode == OFF || prefs.sceneBackup.isEmpty()) {
            prefs.sceneBackup = JSONObject().apply {
                put("filterPaused", prefs.filterPaused)
                put("smartMuteEnabled", prefs.smartMuteEnabled)
                put("muteThreshold", prefs.muteThreshold)
                put("muteDurationSec", prefs.muteDurationSec)
            }.toString()
        }
        when (mode) {
            FOCUS -> {
                // 专注：保留通知但收敛打扰——智能静音更激进，不全局暂停（重要通知仍可穿透）
                prefs.filterPaused = false
                prefs.smartMuteEnabled = true
                prefs.muteThreshold = 3
                prefs.muteDurationSec = 120
            }
            SLEEP -> {
                // 睡眠：只记录不打扰，长静音兜底
                prefs.filterPaused = true
                prefs.smartMuteEnabled = true
                prefs.muteThreshold = 2
                prefs.muteDurationSec = 480
            }
            COMMUTE -> {
                // 通勤：不做静音，恢复常规过滤阈值，靠应用自己筛选
                prefs.filterPaused = false
                prefs.smartMuteEnabled = false
                prefs.muteThreshold = prefs.muteThreshold.coerceAtLeast(5)
                prefs.muteDurationSec = 30
            }
        }
        prefs.sceneMode = mode
        // v1.0.12：睡眠场景不再把整台手机设成静音。改为「睡眠静默」——
        // 只把到来的通知逐条静默消除（通知链路读 prefs.sleepSilence），系统铃声、来电、
        // 闹钟一律不碰。退出任意场景即关闭，绝无残留的全局静音状态。
        prefs.sleepSilence = (mode == SLEEP)
    }

    fun restore(prefs: Prefs) {
        val backup = prefs.sceneBackup
        if (backup.isNotEmpty()) {
            runCatching {
                val j = JSONObject(backup)
                prefs.filterPaused = j.optBoolean("filterPaused", false)
                prefs.smartMuteEnabled = j.optBoolean("smartMuteEnabled", false)
                prefs.muteThreshold = j.optInt("muteThreshold", 5)
                prefs.muteDurationSec = j.optInt("muteDurationSec", 30)
            }
        }
        prefs.sceneBackup = ""
        prefs.sceneMode = OFF
        prefs.sleepSilence = false
    }
}
