package com.xiaodou.notify.listener

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.data.FilterRule
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.engine.ActionExecutor
import com.xiaodou.notify.engine.FeedbackNoticeFilter
import com.xiaodou.notify.engine.MatchContext
import com.xiaodou.notify.engine.MuteController
import com.xiaodou.notify.engine.RuleEngine
import com.xiaodou.notify.engine.SpamScorer
import com.xiaodou.notify.util.DiagnosticProbe
import com.xiaodou.notify.util.ProHelper
import kotlinx.coroutines.launch

/**
 * 通知监听服务：产品技术底座。
 * onNotificationPosted → 加密落库 → 排除判定 → 规则匹配 → AI 打分 → 执行动作。
 */
class NotificationMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "XiaodouDiag"

        var instance: NotificationMonitorService? = null
            private set

        /**
         * 真正的连接状态：只有系统回调 onListenerConnected 才为 true。
         * 注意：不能用 instance != null 判断！服务 onCreate 只代表进程内创建了实例，
         * 并不代表系统真正绑定了 listener。国产 ROM 上常见「实例存在但从未连接」的假连接，
         * 这正是「权限已给、进程也在、但收不到任何通知」的根因。
         */
        @Volatile
        var connected: Boolean = false
            private set

        /** 监听服务是否真正连接到系统（以 onListenerConnected 为准）。 */
        val isConnected: Boolean get() = connected

        /**
         * 读取系统当前实际允许本监听器接收的通知类型（API 33+）。
         * v1.4.3 诊断用：若返回空，说明系统把本监听器允许的类型判为空 —— 一条通知都分发不过来。
         * 这是 v1.4.2 之前 Manifest 写错 default_filter_types（逗号+非法值）留下的后遗症，
         * 需要撤销并重新授予通知使用权让系统重新读取。
         */
        fun allowedTypesText(): String {
            val s = instance ?: return "未知（服务未连接）"
            // 1) 反射尝试不同 API 级别上的方法名（部分 ROM 上不存在或签名不同）
            for (mn in arrayOf("getAllowedNotificationTypes", "getNotificationTypes")) {
                try {
                    val m = s.javaClass.getMethod(mn)
                    val arr = m.invoke(s) as? IntArray
                    if (arr != null) {
                        if (arr.isEmpty()) {
                            return "空 ← 系统不允许任何类型（需卸载重装让系统重读配置）"
                        }
                        // 位标志：conversations=1, alerting=2, silent=4, ongoing=8
                        val parts = mutableListOf<String>()
                        if (arr.any { (it and 1) != 0 }) parts += "conversations"
                        if (arr.any { (it and 2) != 0 }) parts += "alerting"
                        if (arr.any { (it and 4) != 0 }) parts += "silent"
                        if (arr.any { (it and 8) != 0 }) parts += "ongoing"
                        return parts.joinToString("|").ifBlank { "未识别：${arr.joinToString(",")}" }
                    }
                } catch (e: Exception) { }
            }
            // 2) 系统接口读不到时，回退读 APK 声明值 + 当前可见通知数，避免误报「均正常」
            val declared = try {
                val ai = s.packageManager.getServiceInfo(
                    ComponentName(s, NotificationMonitorService::class.java), 0
                )
                ai.metaData?.getString("android.service.notification.default_filter_types") ?: "(未声明)"
            } catch (e: Exception) { "(读取失败)" }
            val active = try { s.activeNotifications?.size ?: -1 } catch (e: Exception) { -1 }
            return "系统接口不可读；本版声明=$declared；当前可见通知=$active 条"
        }

        /**
         * 请求系统重新绑定监听服务。
         * 关键修复：过去用 instance != null 判断，导致「假连接」时永远跳过重绑。
         * 现在只要未真正 connected 就主动 rebind。
         */
        fun rebindIfNeeded(context: Context) {
            if (connected) return
            try {
                val cn = ComponentName(context.applicationContext, NotificationMonitorService::class.java)
                requestRebind(cn)
                android.util.Log.d(TAG, "rebindIfNeeded: requestRebind (connected=$connected)")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "rebindIfNeeded failed: ${e.javaClass.simpleName}")
            }
        }

        /**
         * 强制重连（诊断页/看门狗用）：requestRebind 在部分 ROM 上对「假连接」无效，
         * 通过 disable→enable 组件强制系统重新走一遍绑定流程。这是国产 ROM 上最有效的手段。
         */
        fun forceRebind(context: Context) {
            val cn = ComponentName(context.applicationContext, NotificationMonitorService::class.java)
            try {
                val pm = context.applicationContext.packageManager
                pm.setComponentEnabledSetting(
                    cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    0
                )
                pm.setComponentEnabledSetting(
                    cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                android.util.Log.d(TAG, "forceRebind: component toggled")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "forceRebind toggle failed: ${e.javaClass.simpleName}")
                rebindIfNeeded(context)
            }
        }

        /** 验证码识别正则：6~8 位数字（可含字母），独立成词或紧邻「验证码/.code」上下文 */
        private val CODE_PATTERN = Regex("(?<![A-Za-z0-9])([A-Z0-9]{4,8}|\\d{4,8})(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        private val CODE_HINT = listOf("验证码", "校验码", "动态码", "code", "取件码", "取件")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // v1.7.1：监听服务重建时同样对齐静音状态，覆盖「只有服务存活、无 Activity」的场景
        (applicationContext as? XiaodouApp)?.let { MuteController.recoverIfNeeded(this, it.prefs) }
    }

    override fun onDestroy() {
        instance = null
        (applicationContext as? XiaodouApp)?.prefs?.listenerDisconnectedAt = System.currentTimeMillis()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        android.util.Log.d(TAG, "onListenerConnected: LISTENER REALLY CONNECTED")
        DiagnosticProbe.onListenerConnected(applicationContext)
        val app = applicationContext as? XiaodouApp
        if (app != null) {
            val disc = app.prefs.listenerDisconnectedAt
            // 断开超过 1 分钟视为可能漏记（FR-P4 漏记透明提示）
            if (disc > 0 && System.currentTimeMillis() - disc > 60_000L) {
                app.prefs.pendingMissedSince = disc
            }
            app.prefs.listenerDisconnectedAt = 0L
            app.prefs.listenerConnectedAt = System.currentTimeMillis()
            if (app.prefs.firstRunAt == 0L) app.prefs.firstRunAt = System.currentTimeMillis()
        }
        KeepAliveService.start(applicationContext)
    }

    /**
     * 服务被系统解绑（进程回收/系统清理）时，尽可能立即请求重新绑定。
     * 权限仍在时 requestRebind 可恢复接管，无需用户手动往返系统设置。
     */
    override fun onListenerDisconnected() {
        connected = false
        android.util.Log.w(TAG, "onListenerDisconnected: LISTENER DETACHED")
        DiagnosticProbe.onListenerDisconnected(applicationContext)
        (applicationContext as? XiaodouApp)?.prefs?.listenerDisconnectedAt = System.currentTimeMillis()
        try {
            val cn = ComponentName(applicationContext, NotificationMonitorService::class.java)
            requestRebind(cn)
        } catch (e: Exception) { }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // v1.4.1 诊断探针：必须在「忽略自身通知」之前旁听，否则自测通知会被丢弃。
        // 只记录状态、不改通知正文、不影响任何后续处理链路。
        try { DiagnosticProbe.onNotificationSeen(applicationContext, sbn) } catch (e: Exception) { }
        android.util.Log.d(TAG, "onNotificationPosted: pkg=${sbn.packageName} connected=$connected")

        val app = applicationContext as? XiaodouApp ?: return
        val pkg = sbn.packageName
        if (pkg == applicationContext.packageName) return // 忽略自身通知

        // 排除 App（FR-E8 两档语义）
        if (app.prefs.isExcluded(pkg) && app.prefs.excludeNoRecord) return

        val extras = sbn.notification.extras ?: Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        // 正文多来源兜底：普通文本 → 大文本 → 多行文本 → MessagingStyle 最后一条 → ticker
        val text = extractText(extras)
        // 标题与正文都为空时，只要通知本身有效就记录（用应用名占位），避免漏记
        if (title.isEmpty() && text.isEmpty() && sbn.notification.tickerText.isNullOrEmpty()) {
            // 完全无可读内容的通知（如纯进度条）跳过，避免污染历史
            return
        }
        val finalTitle = title.ifEmpty { sbn.notification.tickerText?.toString() ?: "" }
        val finalText = text.ifEmpty { sbn.notification.tickerText?.toString() ?: "" }

        val appName = try {
            applicationContext.packageManager.getApplicationLabel(
                applicationContext.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { pkg }

        val category = categorize(sbn, finalTitle, finalText)

        app.appScope.launch {
            // 排除第二档：记录但不过滤，直接落库后返回
            val excludedNoFilter = app.prefs.isExcluded(pkg) && !app.prefs.excludeNoRecord

            val ctx = MatchContext(
                pkg, appName, finalTitle, finalText, sbn.postTime,
                RuleEngine.deviceState(applicationContext).first,
                RuleEngine.deviceState(applicationContext).second
            )

            // 1) 加密落库（FR-H1）——加密失败拒绝存储，绝不落明文
            val entity = try {
                NotificationEntity(
                    packageName = pkg,
                    appName = appName,
                    titleEnc = CryptoHelper.encrypt(finalTitle),
                    textEnc = CryptoHelper.encrypt(finalText),
                    timestamp = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
                    channel = sbn.notification.channelId,
                    sysKey = sbn.key,
                    category = category
                )
            } catch (e: Exception) {
                return@launch // 加密失败：不落库，保护隐私
            }
            val dao = app.database.notificationDao()
            val id = dao.insert(entity)
            var saved = entity.copy(id = id)
            // FR-E4：通知落库后主动刷新桌面小部件统计
            try { XiaodouWidgetProvider.refreshAll(applicationContext) } catch (e: Exception) { }

            // FR-H1 滚动清理：最旧优先
            try { performRollingCleanup(app, dao.countAll()) } catch (e: Exception) { }

            // v1.6.0 重要通知守护（Pro）：命中守护词的通知强制保留，
            // 跳过规则匹配、AI 打分、智能静音等一切可能误杀的环节。
            // 这是拦截类 App 最大的焦虑点（怕漏重要通知），放在所有过滤之前优先级最高。
            if (ProHelper.isActive(app.prefs) && app.prefs.vipGuardEnabled) {
                val words = app.prefs.vipWords()
                if (words.isNotEmpty()) {
                    val haystack = "$appName $finalTitle $finalText"
                    if (words.any { haystack.contains(it, ignoreCase = true) }) {
                        // 用户显式守护的反馈通知按普通通知展示，不再受「默认隐藏」约束
                        val guarded = saved.copy(
                            result = Result.KEPT,
                            filterReason = applicationContext.getString(R.string.vip_reason),
                            category = if (category == FeedbackNoticeFilter.CATEGORY) null else saved.category
                        )
                        dao.update(guarded)
                        return@launch
                    }
                }
            }

            if (excludedNoFilter) return@launch // 排除档位2：记录不过滤

            // v1.7.3 系统操作反馈：照常加密落库，但不参与规则/AI/静音判定，
            // 避免截图这类提示消耗静音窗口或触发误拦截。默认在历史/统计中隐藏，开关打开才显示。
            if (category == FeedbackNoticeFilter.CATEGORY) {
                dao.update(saved.copy(filterReason = applicationContext.getString(R.string.feedback_reason)))
                return@launch
            }

            // FR-E4 全局暂停过滤：仅记录，不执行任何规则/AI/静音
            if (app.prefs.filterPaused) return@launch

            // 2) 智能静音（FR-E2）
            if (ActionExecutor.shouldSmartMute(applicationContext, pkg, app.prefs)) {
                dao.update(saved.copy(result = Result.RULE, filterReason = "智能静音"))
                return@launch
            }

            // 3) 规则匹配（FR-R1）
            val rules = app.database.ruleDao().enabledRules()
            val match = RuleEngine.firstMatch(rules, ctx)
            if (match != null) {
                val outcome = ActionExecutor.execute(
                    applicationContext, saved, match.rule.action, match.rule.actionParam,
                    finalText, finalTitle
                )
                if (outcome.applied && outcome.result == Result.RULE) {
                    dao.update(saved.copy(result = outcome.result, filterReason = outcome.reason))
                    // 5 秒撤销按钮（FR-E1）：消除且开启时
                    if (app.prefs.undoEnabled && match.rule.action == Actions.DISMISS) {
                        NotificationBridge.showUndo(applicationContext, id, finalTitle)
                    }
                } else {
                    saved = saved.copy(filterReason = match.reason + "；" + outcome.reason)
                    dao.update(saved)
                }
                // 验证码复制（FR-E3）：即使命中规则也尝试附加复制入口
                maybeCodeCopy(app, category, finalTitle, finalText, saved)
                return@launch
            }

            // 4) AI 打分（FR-A1/A3）
            if (app.prefs.aiEnabled) {
                val feedback = app.database.aiFeedbackDao().recent(500)
                val model = SpamScorer.buildModel(feedback) { CryptoHelper.decrypt(it) }
                val score = SpamScorer.score(finalTitle, finalText, pkg, model)
                if (score >= app.prefs.aiThreshold) {
                    try { cancelNotification(sbn.key) } catch (e: Exception) { }
                    dao.update(saved.copy(result = Result.AI, score = score, filterReason = "AI 判定骚扰 $score%"))
                } else {
                    dao.update(saved.copy(score = score))
                }
            }

            // 5) 验证码复制（FR-E3）
            maybeCodeCopy(app, category, finalTitle, finalText, saved)

            // 6) AI 闲置/充电学习（FR-A3）：低频触发，避免前台卡顿
            maybeLearn(app)
        }
    }


    /** 滚动清理：按保留窗口与条数上限，最旧优先（FR-H1） */
    private suspend fun performRollingCleanup(app: XiaodouApp, total: Int) {
        val dao = app.database.notificationDao()
        // v1.5.0 Pro 门控：免费版历史上限 5000 条 / 30 天，Pro 放开到用户设定值
        val isPro = ProHelper.isActive(app.prefs)
        val max = if (isPro) {
            app.prefs.maxRecords.coerceIn(1000, 500000)
        } else {
            app.prefs.maxRecords.coerceIn(1000, 5000).coerceAtMost(5000)
        }
        if (total > max) dao.deleteOldest(total - max)
        val days = if (isPro) app.prefs.retainDays else app.prefs.retainDays.coerceAtMost(30)
        if (days > 0) dao.deleteBefore(System.currentTimeMillis() - days * 86_400_000L)
    }

    /** AI 学习调度：仅在充电（且用户要求）时触发，两次至少间隔 6 小时（FR-A3） */
    private fun maybeLearn(app: XiaodouApp) {
        val now = System.currentTimeMillis()
        if (now - app.prefs.lastLearnAt < 6 * 3_600_000L) return
        val charging = RuleEngine.deviceState(applicationContext).first
        if (app.prefs.aiLearnOnlyCharging && !charging) return
        app.appScope.launch {
            val feedback = app.database.aiFeedbackDao().recent(500)
            if (feedback.size >= 10) {
                SpamScorer.buildModel(feedback) { CryptoHelper.decrypt(it) }
                app.prefs.lastLearnAt = System.currentTimeMillis()
            }
        }
    }

    /** 验证码识别与复制（FR-E3）：无法给他人通知加按钮时，降级为本 App 通知内一键复制 */
    private fun maybeCodeCopy(
        app: XiaodouApp, category: String?, title: String, text: String, entity: NotificationEntity
    ) {
        if (!app.prefs.codeCopyEnabled || category != "code") return
        val combined = "$title $text"
        if (!CODE_HINT.any { combined.contains(it, ignoreCase = true) }) return
        val code = CODE_PATTERN.findAll(combined)
            .map { it.value }
            .firstOrNull { it.length in 4..8 } ?: return
        NotificationBridge.showCodeCopy(applicationContext, entity.id, code)
    }

    /**
     * 正文多来源兜底。很多 IM/社交 App 把正文放在不同字段，
     * 只取 EXTRA_TEXT 会漏掉大量通知，导致「来通知但历史里看不到」。
     */
    private fun extractText(extras: Bundle): String {
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.isNotEmpty()) {
            val joined = lines.joinToString(" | ") { it.toString() }
            if (joined.isNotBlank()) return joined
        }
        // MessagingStyle：取最后一条消息文本（android.messages 为 Parcelable[] Bundle）
        val msgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        if (!msgs.isNullOrEmpty()) {
            val last = msgs.lastOrNull()
            if (last is Bundle) {
                val m = last.getCharSequence("text")?.toString()
                if (!m.isNullOrBlank()) return m
            }
        }
        return ""
    }

    /** 通知类型分类：验证码 / 常驻 / 媒体 / 普通（FR-H2 类型筛选） */
    private fun categorize(sbn: StatusBarNotification, title: String, text: String): String {
        val combined = "$title $text"
        if (CODE_HINT.any { combined.contains(it, ignoreCase = true) } && CODE_PATTERN.containsMatchIn(combined)) return "code"
        if (sbn.isOngoing) return "ongoing"
        val cat = sbn.notification.category ?: ""
        if (cat == Notification.CATEGORY_TRANSPORT) return "media"
        if (cat == Notification.CATEGORY_CALL) return "call"
        val base = "normal"
        // v1.7.3：在常规分类之后判定系统操作反馈，命中则单独归类（仍走正常落库）
        return if (FeedbackNoticeFilter.isFeedbackNotice(sbn.packageName, title, text, base, sbn.isOngoing))
            FeedbackNoticeFilter.CATEGORY else base
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // FR-H5：通知到达时已快照落库，移除后历史仍可查看。
        // 系统不提供可靠的「发送方撤回」判定，故统一如实标记为「已离开通知栏」，
        // 不虚构「已被撤回」结论（符合 PRD 不夸大能力边界）。
        val app = applicationContext as? XiaodouApp ?: return
        app.appScope.launch {
            val dao = app.database.notificationDao()
            val entity = dao.recent(300).firstOrNull {
                it.sysKey == sbn.key || (it.packageName == sbn.packageName && it.timestamp == sbn.postTime)
            }
            entity?.let { dao.setRemovedReason(it.id, "left_notification_shade") }
        }
    }
}
