package com.xiaodou.notify.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用设置。仅存开关与阈值，不含任何通知内容。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("xiaodou_prefs", Context.MODE_PRIVATE)

    var aiEnabled: Boolean
        get() = sp.getBoolean(KEY_AI_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    /** 是否已初始化开箱即用的默认过滤规则（仅首次安装执行） */
    var defaultRulesSeeded: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_RULES_SEEDED, false)
        set(value) = sp.edit().putBoolean(KEY_DEFAULT_RULES_SEEDED, value).apply()

    var aiThreshold: Int
        get() = sp.getInt(KEY_AI_THRESHOLD, 80)
        set(value) = sp.edit().putInt(KEY_AI_THRESHOLD, value).apply()

    var undoEnabled: Boolean
        get() = sp.getBoolean(KEY_UNDO, true)
        set(value) = sp.edit().putBoolean(KEY_UNDO, value).apply()

    var smartMuteEnabled: Boolean
        get() = sp.getBoolean(KEY_SMART_MUTE, false)
        set(value) = sp.edit().putBoolean(KEY_SMART_MUTE, value).apply()

    var codeCopyEnabled: Boolean
        get() = sp.getBoolean(KEY_CODE_COPY, true)
        set(value) = sp.edit().putBoolean(KEY_CODE_COPY, value).apply()

    /**
     * 是否显示「系统操作反馈」通知（截图已保存 / 录屏 / 投屏等系统自动生成、用户无需专门查看的提示）。
     * 默认 false：此类通知照常加密落库，但在历史列表、统计与小部件中默认隐藏，
     * 仅在用户主动打开此开关后才显示。开关状态存 SharedPreferences，重启后保留。
     */
    var showFeedbackNotices: Boolean
        get() = sp.getBoolean(KEY_SHOW_FEEDBACK_NOTICES, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_FEEDBACK_NOTICES, value).apply()

    var guideShown: Boolean
        get() = sp.getBoolean(KEY_GUIDE_SHOWN, false)
        set(value) = sp.edit().putBoolean(KEY_GUIDE_SHOWN, value).apply()

    /** 排除 App 包名，逗号分隔 */
    var excludedApps: String
        get() = sp.getString(KEY_EXCLUDED, "") ?: ""
        set(value) = sp.edit().putString(KEY_EXCLUDED, value).apply()

    fun isExcluded(pkg: String): Boolean =
        excludedApps.split(",").any { it.isNotBlank() && it == pkg }

    /** 排除语义：true=不记录也不处理；false=记录但不做任何过滤（默认不记录） */
    var excludeNoRecord: Boolean
        get() = sp.getBoolean(KEY_EXCLUDE_MODE, true)
        set(value) = sp.edit().putBoolean(KEY_EXCLUDE_MODE, value).apply()

    // —— FR-H1 历史滚动清理 ——
    /** 保留窗口（天），0=不限 */
    var retainDays: Int
        get() = sp.getInt(KEY_RETAIN_DAYS, 30)
        set(value) = sp.edit().putInt(KEY_RETAIN_DAYS, value).apply()

    /** 记录条数上限，超出按最旧优先清理 */
    var maxRecords: Int
        get() = sp.getInt(KEY_MAX_RECORDS, 50000)
        set(value) = sp.edit().putInt(KEY_MAX_RECORDS, value).apply()

    // —— FR-E2 智能静音可配 ——
    var muteThreshold: Int
        get() = sp.getInt(KEY_MUTE_THRESHOLD, 5)
        set(value) = sp.edit().putInt(KEY_MUTE_THRESHOLD, value).apply()

    var muteWindowSec: Int
        get() = sp.getInt(KEY_MUTE_WINDOW, 60)
        set(value) = sp.edit().putInt(KEY_MUTE_WINDOW, value).apply()

    var muteDurationSec: Int
        get() = sp.getInt(KEY_MUTE_DURATION, 30)
        set(value) = sp.edit().putInt(KEY_MUTE_DURATION, value).apply()

    /** v1.7.1：静音发起前的铃声模式，-1=当前没有由本模块发起的静音 */
    var muteOriginalMode: Int
        get() = sp.getInt(KEY_MUTE_ORIGINAL_MODE, -1)
        set(value) = sp.edit().putInt(KEY_MUTE_ORIGINAL_MODE, value).apply()

    /** v1.7.1：静音到期时刻（epoch ms），0=无进行中静音。进程重启后据此恢复 */
    var muteUntil: Long
        get() = sp.getLong(KEY_MUTE_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_MUTE_UNTIL, value).apply()

    /** v1.7.1：静音来源（smart/rule），仅用于诊断，不参与判定 */
    var muteSource: String
        get() = sp.getString(KEY_MUTE_SOURCE, "") ?: ""
        set(value) = sp.edit().putString(KEY_MUTE_SOURCE, value).apply()

    // —— FR-E5 应用锁 ——
    var appLockEnabled: Boolean
        get() = sp.getBoolean(KEY_APP_LOCK, false)
        set(value) = sp.edit().putBoolean(KEY_APP_LOCK, value).apply()

    /** 应用锁有效期（分钟），0=每次都验证 */
    var appLockTimeoutMin: Int
        get() = sp.getInt(KEY_APP_LOCK_TIMEOUT, 1)
        set(value) = sp.edit().putInt(KEY_APP_LOCK_TIMEOUT, value).apply()

    var lastUnlockAt: Long
        get() = sp.getLong(KEY_LAST_UNLOCK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UNLOCK, value).apply()

    /** 监听服务最近一次连接到系统的时间（0=从未连接），用于设置页显示真实运行状态 */
    var listenerConnectedAt: Long
        get() = sp.getLong(KEY_LISTENER_CONNECTED_AT, 0L)
        set(value) = sp.edit().putLong(KEY_LISTENER_CONNECTED_AT, value).apply()

    /** 应用锁独立 PIN：仅保存加盐哈希，绝不存明文 */
    var appLockPinHash: String
        get() = sp.getString(KEY_APP_LOCK_PIN_HASH, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_LOCK_PIN_HASH, value).apply()

    /** PIN 加盐，首次设置 PIN 时生成并固定保存 */
    var appLockPinSalt: String
        get() = sp.getString(KEY_APP_LOCK_PIN_SALT, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_LOCK_PIN_SALT, value).apply()

    // —— v1.4.0 应用锁增强：离开时长 / 解锁方式 / 备用恢复 ——
    /**
     * 离开应用多久后重新进入才验证，单位毫秒。
     * 0 = 每次进入都验证（老行为）；15000 / 60000 / 180000 = 15秒 / 1分钟 / 3分钟。
     */
    var appLockAwayMs: Long
        get() = sp.getLong(KEY_APP_LOCK_AWAY_MS, 0L)
        set(value) = sp.edit().putLong(KEY_APP_LOCK_AWAY_MS, value).apply()

    /** 解锁方式：0=PIN/密码 1=生物识别(指纹/人脸) 2=两者皆可(默认) */
    var appLockMethod: Int
        get() = sp.getInt(KEY_APP_LOCK_METHOD, 2)
        set(value) = sp.edit().putInt(KEY_APP_LOCK_METHOD, value).apply()

    /** 退到后台的时刻（毫秒），离开时长判定由 AppLockPolicy 负责 */
    var lastBackgroundAt: Long
        get() = sp.getLong(KEY_LAST_BACKGROUND_AT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_BACKGROUND_AT, value).apply()

    /** 备用恢复答案的加盐哈希：忘记 PIN 时凭安全问题重置，避免启用后无法恢复 */
    var recoveryHash: String
        get() = sp.getString(KEY_RECOVERY_HASH, "") ?: ""
        set(value) = sp.edit().putString(KEY_RECOVERY_HASH, value).apply()

    var recoverySalt: String
        get() = sp.getString(KEY_RECOVERY_SALT, "") ?: ""
        set(value) = sp.edit().putString(KEY_RECOVERY_SALT, value).apply()

    /** 备用恢复问题的索引（对应 LockRecovery.QUESTIONS），-1=未设置 */
    var recoveryQuestionIndex: Int
        get() = sp.getInt(KEY_RECOVERY_QUESTION, -1)
        set(value) = sp.edit().putInt(KEY_RECOVERY_QUESTION, value).apply()

    // —— FR-A3 离线学习调度 ——
    var aiLearnOnlyCharging: Boolean
        get() = sp.getBoolean(KEY_AI_LEARN_CHARGING, true)
        set(value) = sp.edit().putBoolean(KEY_AI_LEARN_CHARGING, value).apply()

    var lastLearnAt: Long
        get() = sp.getLong(KEY_LAST_LEARN, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_LEARN, value).apply()

    /** 首次启动时间，用于漏记提示的时段起点 */
    var firstRunAt: Long
        get() = sp.getLong(KEY_FIRST_RUN, 0L)
        set(value) = sp.edit().putLong(KEY_FIRST_RUN, value).apply()

    /** 监听服务断开时刻（用于漏记提示） */
    var listenerDisconnectedAt: Long
        get() = sp.getLong(KEY_LISTENER_DISCONNECTED, 0L)
        set(value) = sp.edit().putLong(KEY_LISTENER_DISCONNECTED, value).apply()

    /** 待提示的漏记起始时间，0=无 */
    var pendingMissedSince: Long
        get() = sp.getLong(KEY_PENDING_MISSED, 0L)
        set(value) = sp.edit().putLong(KEY_PENDING_MISSED, value).apply()

    // —— FR-E6 多主题 ——
    /** 深浅模式：0=跟随系统 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, 0)
        set(value) = sp.edit().putInt(KEY_THEME_MODE, value).apply()

    /** 配色索引：0=蓝 1=青绿 2=紫 3=珊瑚 4=燕麦 5=藕荷 6=藏青 */
    var themeColor: Int
        get() = sp.getInt(KEY_THEME_COLOR, 0)
        set(value) = sp.edit().putInt(KEY_THEME_COLOR, value).apply()

    /** 桌面图标索引：0=经典 1=极简铃铛 2=小豆荚 3=漏斗 4=信封 */
    var launcherIcon: Int
        get() = sp.getInt(KEY_LAUNCHER_ICON, 0)
        set(value) = sp.edit().putInt(KEY_LAUNCHER_ICON, value).apply()

    // —— v1.5.0 Pro 买断 ——
    /** 是否已成功激活过 Pro（周卡过期后仍为 true，靠到期时间判定有效性） */
    var proActivated: Boolean
        get() = sp.getBoolean(KEY_PRO_ACTIVATED, false)
        set(value) = sp.edit().putBoolean(KEY_PRO_ACTIVATED, value).apply()

    var proForever: Boolean
        get() = sp.getBoolean(KEY_PRO_FOREVER, false)
        set(value) = sp.edit().putBoolean(KEY_PRO_FOREVER, value).apply()

    /** 周卡到期时刻；永久版为 Long.MAX_VALUE */
    var proExpireAt: Long
        get() = sp.getLong(KEY_PRO_EXPIRE, 0L)
        set(value) = sp.edit().putLong(KEY_PRO_EXPIRE, value).apply()

    /** 已激活的码（仅用于展示，不参与校验） */
    var proCode: String
        get() = sp.getString(KEY_PRO_CODE, "") ?: ""
        set(value) = sp.edit().putString(KEY_PRO_CODE, value).apply()

    // —— v1.6.0 重要通知守护（Pro）——
    var vipGuardEnabled: Boolean
        get() = sp.getBoolean(KEY_VIP_GUARD_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_VIP_GUARD_ENABLED, value).apply()

    /** 逗号分隔的守护词，匹配标题/正文/应用名 */
    var vipGuardWords: String
        get() = sp.getString(KEY_VIP_GUARD_WORDS, "") ?: ""
        set(value) = sp.edit().putString(KEY_VIP_GUARD_WORDS, value).apply()

    /** 解析后的守护词列表（去空白） */
    fun vipWords(): List<String> =
        vipGuardWords.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }

    // —— FR-E4 桌面小部件：全局暂停过滤 ——
    /** 暂停期间只记录不执行任何过滤/动作 */
    var filterPaused: Boolean
        get() = sp.getBoolean(KEY_FILTER_PAUSED, false)
        set(value) = sp.edit().putBoolean(KEY_FILTER_PAUSED, value).apply()

    // —— v1.4.1 诊断探针：只记录探针元数据与文本日志，不存储任何通知正文 ——
    /** 本次自测的随机标记；空=无进行中自测 */
    var probeToken: String
        get() = sp.getString(KEY_PROBE_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_PROBE_TOKEN, value).apply()

    /** 自测发起时刻 */
    var probeStartAt: Long
        get() = sp.getLong(KEY_PROBE_START, 0L)
        set(value) = sp.edit().putLong(KEY_PROBE_START, value).apply()

    /** 自测结果：0=未开始 1=等待捕获 2=已捕获 */
    var probeState: Int
        get() = sp.getInt(KEY_PROBE_STATE, 0)
        set(value) = sp.edit().putInt(KEY_PROBE_STATE, value).apply()

    /** 会话观察是否激活（统计任意来源通知，用于验证监听链路） */
    var probeSessionActive: Boolean
        get() = sp.getBoolean(KEY_PROBE_SESSION, false)
        set(value) = sp.edit().putBoolean(KEY_PROBE_SESSION, value).apply()

    var probeSessionStartAt: Long
        get() = sp.getLong(KEY_PROBE_SESSION_START, 0L)
        set(value) = sp.edit().putLong(KEY_PROBE_SESSION_START, value).apply()

    /** 会话内捕获到的通知条数 */
    var probeSessionCount: Int
        get() = sp.getInt(KEY_PROBE_SESSION_COUNT, 0)
        set(value) = sp.edit().putInt(KEY_PROBE_SESSION_COUNT, value).apply()

    /** 会话内最后一次捕获的包名与时间 */
    var probeLastPkg: String
        get() = sp.getString(KEY_PROBE_LAST_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_PROBE_LAST_PKG, value).apply()

    var probeLastAt: Long
        get() = sp.getLong(KEY_PROBE_LAST_AT, 0L)
        set(value) = sp.edit().putLong(KEY_PROBE_LAST_AT, value).apply()

    /** 探针事件日志（换行分隔，最多保留 40 条） */
    var probeLog: String
        get() = sp.getString(KEY_PROBE_LOG, "") ?: ""
        set(value) = sp.edit().putString(KEY_PROBE_LOG, value).apply()

    /** 追加一条带时间戳的探针事件（只写状态类信息，不写通知正文） */
    fun appendProbeLog(message: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val lines = probeLog.lines().filter { it.isNotBlank() }.toMutableList()
        lines.add("$ts $message")
        while (lines.size > 40) lines.removeAt(0)
        probeLog = lines.joinToString("\n")
    }

    companion object {
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_DEFAULT_RULES_SEEDED = "default_rules_seeded"
        private const val KEY_AI_THRESHOLD = "ai_threshold"
        private const val KEY_UNDO = "undo_enabled"
        private const val KEY_SMART_MUTE = "smart_mute"
        private const val KEY_CODE_COPY = "code_copy"
        private const val KEY_SHOW_FEEDBACK_NOTICES = "show_feedback_notices"
        private const val KEY_GUIDE_SHOWN = "guide_shown"
        private const val KEY_EXCLUDED = "excluded_apps"
        private const val KEY_EXCLUDE_MODE = "exclude_no_record"
        private const val KEY_RETAIN_DAYS = "retain_days"
        private const val KEY_MAX_RECORDS = "max_records"
        private const val KEY_MUTE_THRESHOLD = "mute_threshold"
        private const val KEY_MUTE_WINDOW = "mute_window_sec"
        private const val KEY_MUTE_DURATION = "mute_duration_sec"
        private const val KEY_MUTE_ORIGINAL_MODE = "mute_original_mode"
        private const val KEY_MUTE_UNTIL = "mute_until"
        private const val KEY_MUTE_SOURCE = "mute_source"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_APP_LOCK_TIMEOUT = "app_lock_timeout_min"
        private const val KEY_LAST_UNLOCK = "last_unlock_at"
        private const val KEY_LISTENER_CONNECTED_AT = "listener_connected_at"
        private const val KEY_APP_LOCK_PIN_HASH = "app_lock_pin_hash"
        private const val KEY_APP_LOCK_PIN_SALT = "app_lock_pin_salt"
        private const val KEY_APP_LOCK_AWAY_MS = "app_lock_away_ms"
        private const val KEY_APP_LOCK_METHOD = "app_lock_method"
        private const val KEY_LAST_BACKGROUND_AT = "last_background_at"
        private const val KEY_RECOVERY_HASH = "recovery_hash"
        private const val KEY_RECOVERY_SALT = "recovery_salt"
        private const val KEY_RECOVERY_QUESTION = "recovery_question_index"
        private const val KEY_AI_LEARN_CHARGING = "ai_learn_charging"
        private const val KEY_LAST_LEARN = "last_learn_at"
        private const val KEY_FIRST_RUN = "first_run_at"
        private const val KEY_LISTENER_DISCONNECTED = "listener_disconnected_at"
        private const val KEY_PENDING_MISSED = "pending_missed_since"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_LAUNCHER_ICON = "launcher_icon"
        private const val KEY_PRO_ACTIVATED = "pro_activated"
        private const val KEY_PRO_FOREVER = "pro_forever"
        private const val KEY_PRO_EXPIRE = "pro_expire"
        private const val KEY_PRO_CODE = "pro_code"
        private const val KEY_FILTER_PAUSED = "filter_paused"
        private const val KEY_PROBE_TOKEN = "probe_token"
        private const val KEY_PROBE_START = "probe_start_at"
        private const val KEY_PROBE_STATE = "probe_state"
        private const val KEY_PROBE_SESSION = "probe_session_active"
        private const val KEY_PROBE_SESSION_START = "probe_session_start_at"
        private const val KEY_PROBE_SESSION_COUNT = "probe_session_count"
        private const val KEY_PROBE_LAST_PKG = "probe_last_pkg"
        private const val KEY_PROBE_LAST_AT = "probe_last_at"
        private const val KEY_PROBE_LOG = "probe_log"
        private const val KEY_VIP_GUARD_ENABLED = "vip_guard_enabled"
        private const val KEY_VIP_GUARD_WORDS = "vip_guard_words"
    }
}
