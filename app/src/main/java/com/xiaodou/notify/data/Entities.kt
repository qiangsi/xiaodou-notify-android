package com.xiaodou.notify.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 处理结果：保留 / 被规则过滤 / 被 AI 过滤 */
object Result {
    const val KEPT = "kept"
    const val RULE = "rule"
    const val AI = "ai"
}

/**
 * 通知记录。标题与正文以 AES-GCM 密文保存（[titleEnc]/[textEnc]），
 * 应用名与包名为明文以便按 App 搜索与展示，符合「数据不出设备」。
 */
@Entity(tableName = "notifications", indices = [Index("timestamp"), Index("packageName")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val titleEnc: String,
    val textEnc: String,
    val timestamp: Long,
    val channel: String? = null,
    val result: String = Result.KEPT,
    val filterReason: String? = null,
    val score: Int = 0,
    /** null=未标注, 1=骚扰, 0=正常 */
    val marked: Int? = null,
    /** 系统移除原因（用于识别被撤回/删除）：recalled=发送方撤回，dismissed=用户划掉 */
    val removedReason: String? = null,
    val bigTextEnc: String? = null,
    /** 系统通知 key，供 cancelNotification 精确消除；老数据为 null */
    val sysKey: String? = null,
    /** 通知类型/渠道分类：验证码、常驻、媒体等 */
    val category: String? = null
)

/**
 * 过滤规则。条件以 AND 组合；命中后按顺序执行一个或多个动作。
 */
@Entity(tableName = "rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** 逗号分隔的包名，空=全部应用 */
    val packageNames: String = "",
    /** 包含任意关键词，逗号分隔，空=不限 */
    val keywordInclude: String = "",
    /** 排除关键词，逗号分隔 */
    val keywordExclude: String = "",
    /** 生效起止分钟(0-1439)，-1=全天；支持跨零点 */
    val timeStartMin: Int = -1,
    val timeEndMin: Int = -1,
    /** 生效星期，逗号分隔(1=周日...7=周六)，空=每天 */
    val daysOfWeek: String = "",
    val chargingOnly: Boolean = false,
    val screenOffOnly: Boolean = false,
    /** 动作 key，见 Actions */
    val action: String = Actions.DISMISS,
    /** 动作附加参数（推迟分钟/铃声/替换文本/URL） */
    val actionParam: String = "",
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** AI 标注样本，供本地学习使用 */
@Entity(tableName = "ai_feedback")
data class AiFeedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val textEnc: String,
    val label: Int, // 1=骚扰, 0=正常
    val timestamp: Long = System.currentTimeMillis()
)

/** 动作常量 */
object Actions {
    const val KEEP = "keep"
    const val DISMISS = "dismiss"
    const val MUTE = "mute"
    const val SNOOZE = "snooze"
    const val REPLACE = "replace"
    const val RINGTONE = "ringtone"
    const val TTS = "tts"
    const val CALL_UI = "call_ui"
    const val TAP_BUTTON = "tap_button"
    const val OPEN = "open"
    const val WEBHOOK = "webhook"

    val ALL = listOf(KEEP, DISMISS, MUTE, SNOOZE, REPLACE, RINGTONE, TTS, CALL_UI, TAP_BUTTON, OPEN, WEBHOOK)
}
