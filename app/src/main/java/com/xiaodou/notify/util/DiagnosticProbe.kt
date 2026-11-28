package com.xiaodou.notify.util

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.listener.KeepAliveService
import com.xiaodou.notify.listener.NotificationMonitorService
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * v1.4.1 诊断探针。
 *
 * 设计目标：只回答一个问题——通知到底卡在哪一层？
 *  1) 自测：应用给自己发一条带随机 token 的通知，监听服务若在
 *     "忽略自身通知"之前捕获到 token，说明 权限+服务绑定+回调 整条链路是通的；
 *  2) 会话观察：统计任意来源通知的捕获数，验证真实通知是否进入回调；
 *  3) 静态采集：权限、连接时间、过滤配置、事件日志，一次性汇总成可复制报告。
 *
 * 该类不改动任何通知处理逻辑，只在 onNotificationPosted 的最前面"旁听"，
 * 且只写状态信息，绝不存储通知正文。
 */
object DiagnosticProbe {

    const val STATE_NONE = 0
    const val STATE_PENDING = 1
    const val STATE_CAPTURED = 2

    private const val CH_PROBE = "diagnostic_probe"
    private const val NOTIFICATION_ID = 9001
    private const val TOKEN_PREFIX = "MDPROBE-"

    // ------------------------------------------------------------------
    // 钩子：由 NotificationMonitorService 调用
    // ------------------------------------------------------------------

    /** 在 onListenerConnected 里调用，记录连接事件 */
    fun onListenerConnected(ctx: Context) {
        prefs(ctx)?.appendProbeLog("LISTENER_CONNECTED")
    }

    /** 在 onListenerDisconnected 里调用，记录断开事件 */
    fun onListenerDisconnected(ctx: Context) {
        prefs(ctx)?.appendProbeLog("LISTENER_DISCONNECTED")
    }

    /**
     * 在 onNotificationPosted 的最开头调用（必须在 pkg==self 的 return 之前），
     * 否则自测通知会被"忽略自身通知"直接丢弃，测试永远失败。
     */
    fun onNotificationSeen(ctx: Context, sbn: StatusBarNotification) {
        val p = prefs(ctx) ?: return
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val combined = "$title $text"

        // 1) 自测 token 命中 → 证明监听链路通畅
        val token = p.probeToken
        if (token.isNotBlank() && p.probeState == STATE_PENDING && combined.contains(token, ignoreCase = true)) {
            p.probeState = STATE_CAPTURED
            p.appendProbeLog("SELF_PROBE_CAPTURED")
        }

        // 2) 会话观察：统计任意来源通知（只记状态，不记正文）
        if (p.probeSessionActive) {
            p.probeSessionCount = p.probeSessionCount + 1
            p.probeLastPkg = sbn.packageName
            p.probeLastAt = System.currentTimeMillis()
            p.appendProbeLog("SESSION_CAPTURED pkg=${sbn.packageName}")
        }
    }

    // ------------------------------------------------------------------
    // 自测动作：由诊断页调用
    // ------------------------------------------------------------------

    /** 发起一次端到端自测，返回本次 token（用于让用户确认通知内容） */
    fun startSelfProbe(ctx: Context): String {
        val p = prefs(ctx) ?: return ""
        val token = TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT)
        p.probeToken = token
        p.probeStartAt = System.currentTimeMillis()
        p.probeState = STATE_PENDING
        p.appendProbeLog("SELF_PROBE_SENT")

        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return token.also { p.appendProbeLog("PROBE_FAILED no NotificationManager") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_PROBE, "诊断探针", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "仅用于自测通知监听链路，可关闭"
            }
            nm.createNotificationChannel(ch)
        }
        val notification = Notification.Builder(ctx, CH_PROBE)
            .setContentTitle(ctx.getString(R.string.probe_notification_title))
            .setContentText(token)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            p.appendProbeLog("PROBE_SEND_EXCEPTION ${e.javaClass.simpleName}")
        }
        return token
    }

    /** 读取自测结果：返回 0=未开始 1=等待中（超时） 2=已捕获 */
    fun probeState(ctx: Context): Int = prefs(ctx)?.probeState ?: STATE_NONE

    /** 清掉自测通知，避免长期驻留通知栏 */
    fun clearProbeNotification(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) { }
    }

    /** 开始/停止会话观察 */
    fun setSession(ctx: Context, active: Boolean) {
        val p = prefs(ctx) ?: return
        p.probeSessionActive = active
        if (active) {
            p.probeSessionStartAt = System.currentTimeMillis()
            p.probeSessionCount = 0
            p.probeLastPkg = ""
            p.probeLastAt = 0L
            p.appendProbeLog("SESSION_START")
        } else {
            p.appendProbeLog("SESSION_STOP count=${p.probeSessionCount}")
        }
    }

    // ------------------------------------------------------------------
    // 报告生成
    // ------------------------------------------------------------------

    /** 生成诊断报告全文（可直接复制发给开发者） */
    fun buildReport(ctx: Context): String {
        val p = prefs(ctx)
            ?: return "诊断失败：无法获取 XiaodouApp 实例"
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // 顶部一句话诊断：把最关键的状态先摆出来，避免用户漏看
        val topListenerOn = isListenerEnabled(ctx)
        val topConnected = NotificationMonitorService.isConnected
        val topAllowed = NotificationMonitorService.allowedTypesText()
        val allowedEmpty = topAllowed.startsWith("空")
        val allowedUnknown = topAllowed.contains("不可读") || topAllowed.contains("未知") || topAllowed.contains("读取失败")
        val topState = p.probeState
        val topSessionCount = p.probeSessionCount
        val noCaptureAtAll = topState != STATE_CAPTURED && topSessionCount == 0 &&
            runBlocking { try { XiaodouApp.instance.database.notificationDao().countAll() } catch (e: Exception) { 1 } } == 0
        val topRinger = ringerStateText(ctx)
        val phoneIsSilent = isPhoneSilent(ctx)
        val headline = when {
            phoneIsSilent -> "🔇 手机当前处于静音/低音量 —— 用设置页「恢复手机铃声」调回响铃（见 1.5 节）；本 App 不会主动静音"
            !topListenerOn -> "❌ 通知使用权未授予 —— 去系统「通知使用权」打开小豆通知"
            !topConnected -> "❌ 假连接：权限已给，但监听服务没有真正连上系统"
            allowedEmpty -> "❌ 通知类型被系统判为空 —— 服务已连上但收不到任何通知，需卸载重装让系统重读配置"
            noCaptureAtAll -> "❌ 服务已连上但一条通知都没收到 —— 权限/类型看似正常，系统未把通知分发过来（见第7节）"
            allowedUnknown -> "⚠️ 无法读取系统通知类型，无法确认；请看下方的声明值与系统设置里的通知类型开关"
            else -> "✅ 权限、连接、通知类型均正常 —— 若仍不拦截，问题在过滤配置层"
        }

        sb.appendLine("===== 小豆通知 · 诊断报告 =====")
        sb.appendLine("生成时间：${fmt.format(Date(now))}")
        sb.appendLine("★一句话诊断：$headline")
        sb.appendLine()

        // 1. 设备环境
        sb.appendLine("【1. 设备环境】")
        sb.appendLine("厂商 / 型号：${Build.MANUFACTURER} / ${Build.MODEL}")
        sb.appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val version = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (versionCode ${pi.versionCode})"
        } catch (e: Exception) { "未知" }
        sb.appendLine("应用版本：$version")
        sb.appendLine("包名：${ctx.packageName}")
        sb.appendLine()

        // 1.5 铃声状态（v1.7.14：定位「手机被静音」投诉）
        val legacyFlag = try { (ctx.applicationContext as? XiaodouApp)?.prefs?.legacyMuteRecovered } catch (e: Exception) { null }
        sb.appendLine("【1.5 铃声状态】")
        sb.appendLine("★当前铃声模式：$topRinger")
        sb.appendLine("  铃声音量：${streamVolume(ctx, android.media.AudioManager.STREAM_RING)}")
        sb.appendLine("  通知音量：${streamVolume(ctx, android.media.AudioManager.STREAM_NOTIFICATION)}")
        sb.appendLine("  旧版静音迁移已执行：${legacyFlag ?: "未知"}（true=本 App 只在首次启动时恢复过一次，之后绝不碰铃声）")
        sb.appendLine()

        // 1.6 铃声黑匣子（v1.7.15：抓「谁把手机静音了」的现场证据）
        val trace = p.ringerTraceLog.lines().filter { it.isNotBlank() }
        sb.appendLine("【1.6 铃声变化黑匣子】（最近 ${trace.size} 条 / 上限 60）")
        sb.appendLine("  说明：每次铃声模式被改动都会自动记录现场（时间/旧→新/勿扰/前台App/来源）。")
        sb.appendLine("  「来源=本App」=本 App 恢复动作；「系统勿扰」=系统 DND；其它=用户音量键或别的 App。")
        if (trace.isEmpty()) {
            sb.appendLine("  （暂无事件：黑匣子运行中但未捕捉到铃声变化，说明期间没人改过铃声模式）")
        } else {
            trace.takeLast(20).forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()

        // 2. 权限状态
        val listenerOn = isListenerEnabled(ctx)
        val postOn = hasPostNotificationPermission(ctx)
        val listenersValue = try {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "(空)"
        } catch (e: Exception) { "(读取失败)" }
        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ctx.getSystemService(android.os.PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            } catch (e: Exception) { false }
        } else true
        sb.appendLine("【2. 权限状态】")
        sb.appendLine("★通知使用权(Notification access)：${if (listenerOn) "已授予" else "未授予 ← 最常见根因"}")
        sb.appendLine("  系统值：${listenersValue.take(300)}")
        sb.appendLine("通知发送权限(POST_NOTIFICATIONS)：${if (postOn) "已授予" else "未授予（自测通知可能发不出）"}")
        sb.appendLine("电池优化白名单：${if (batteryIgnored) "已加入" else "未加入（后台可能被限制）"}")
        sb.appendLine()

        // 3. 服务状态
        val connected = NotificationMonitorService.isConnected
        val ca = p.listenerConnectedAt
        val da = p.listenerDisconnectedAt
        sb.appendLine("【3. 服务状态】")
        sb.appendLine("监听真正连接(onListenerConnected)：${if (connected) "已连接 ✅" else "未连接 ❌ ← 重点"}")
        sb.appendLine("允许的通知类型：${NotificationMonitorService.allowedTypesText()}")
        sb.appendLine("最近连接时间：${if (ca > 0) "${fmt.format(Date(ca))}（${(now - ca) / 60000} 分钟前）" else "从未连接"}")
        sb.appendLine("最近断开(onListenerDisconnected)：${if (da > 0) fmt.format(Date(da)) else "无"}")
        sb.appendLine("保活前台服务：${serviceState(ctx, KeepAliveService::class.java.name)}")
        sb.appendLine()

        // 4. 过滤配置
        val enabledRules = try {
            runBlocking { (XiaodouApp.instance.database.ruleDao().enabledRules()).size }
        } catch (e: Exception) { -1 }
        val historyCount = try {
            runBlocking { XiaodouApp.instance.database.notificationDao().countAll() }
        } catch (e: Exception) { -1 }
        sb.appendLine("【4. 过滤配置】")
        sb.appendLine("全局暂停过滤：${if (p.filterPaused) "是（此时只记录不过滤）" else "否"}")
        sb.appendLine("AI 骚扰识别：${if (p.aiEnabled) "开启" else "关闭"}，阈值 ${p.aiThreshold}%")
        sb.appendLine("默认规则已注入：${if (p.defaultRulesSeeded) "是" else "否"}")
        sb.appendLine("已启用规则数：$enabledRules")
        sb.appendLine("历史记录数：$historyCount")
        sb.appendLine("排除名单：${p.excludedApps.ifBlank { "空" }}（${if (p.excludeNoRecord) "不记录不过滤" else "记录不过滤"}）")
        sb.appendLine()

        // 5. 探针结果
        val state = p.probeState
        val stateText = when (state) {
            STATE_CAPTURED -> "已捕获 ✅（监听链路通）"
            STATE_PENDING -> "等待中 / 超时未捕获 ❌"
            else -> "未开始"
        }
        sb.appendLine("【5. 自探针结果】")
        sb.appendLine("端到端自测：$stateText")
        if (p.probeStartAt > 0) sb.appendLine("自测发起：${fmt.format(Date(p.probeStartAt))}")
        if (state == STATE_CAPTURED && p.probeToken.isNotBlank()) {
            sb.appendLine("本次标记：${p.probeToken}")
        }
        sb.appendLine("会话观察：${if (p.probeSessionActive) "进行中" else "未开始"}，已捕获 ${p.probeSessionCount} 条")
        if (p.probeLastPkg.isNotBlank()) {
            sb.appendLine("最后捕获来源：${p.probeLastPkg} @ ${fmt.format(Date(p.probeLastAt))}")
        }
        sb.appendLine()

        // 6. 事件日志
        sb.appendLine("【6. 事件日志（最近 20 条）】")
        val log = p.probeLog
        if (log.isBlank()) {
            sb.appendLine("（无任何事件，说明监听服务从未回调过）")
        } else {
            log.lines().takeLast(20).forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine(conclusion(listenerOn, connected, allowedEmpty, ca, state, p.probeSessionCount, p.filterPaused, enabledRules))
        return sb.toString()
    }

    /** 抓取与应用相关的 logcat 行（失败时给出 adb 命令兜底） */
    fun captureLogcat(): String {
        return try {
            val pid = android.os.Process.myPid()
            var raw = exec("logcat -d -t 500 --pid=$pid")
            if (raw.isBlank()) raw = exec("logcat -d -t 500")
            if (raw.isBlank()) {
                return "（应用内读不到 logcat。请在电脑上执行：\nadb logcat | grep -E \"Xiaodou|NotificationListener|SecurityException\"）"
            }
            val filtered = raw.lines().filter { line ->
                line.contains("xiaodou", true) ||
                    line.contains("notificationlistener", true) ||
                    line.contains("securityexception", true) ||
                    line.contains("notifymonitor", true) ||
                    line.contains("webhook", true)
            }.takeLast(80).joinToString("\n")
            filtered.ifBlank { "（logcat 中没有与本应用相关的条目）" }
        } catch (e: Exception) {
            "logcat 读取失败：${e.javaClass.simpleName} ${e.message ?: ""}\n可在电脑上执行：adb logcat | grep -E \"Xiaodou|NotificationListener\""
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun prefs(ctx: Context): Prefs? =
        (ctx.applicationContext as? XiaodouApp)?.prefs

    private fun serviceState(ctx: Context, className: String): String = try {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val running = am?.getRunningServices(200)?.any { it.service.className == className } == true
        if (running) "运行中" else "未运行（看门狗会尝试恢复）"
    } catch (e: Exception) { "无法读取" }

    private fun audioManager(ctx: Context): android.media.AudioManager? =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    private fun ringerStateText(ctx: Context): String = try {
        when (audioManager(ctx)?.ringerMode) {
            android.media.AudioManager.RINGER_MODE_SILENT -> "静音（SILENT）🔇"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "振动（VIBRATE）"
            android.media.AudioManager.RINGER_MODE_NORMAL -> "响铃（NORMAL）"
            else -> "未知"
        }
    } catch (e: Exception) { "读取失败" }

    private fun isPhoneSilent(ctx: Context): Boolean = try {
        val am = audioManager(ctx) ?: return false
        val modeSilent = am.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT ||
            am.ringerMode == android.media.AudioManager.RINGER_MODE_VIBRATE
        val volLow = am.getStreamVolume(android.media.AudioManager.STREAM_RING) < 1
        modeSilent || volLow
    } catch (e: Exception) { false }

    private fun streamVolume(ctx: Context, stream: Int): String = try {
        val am = audioManager(ctx) ?: return "未知"
        "${am.getStreamVolume(stream)} / ${am.getStreamMaxVolume(stream)}"
    } catch (e: Exception) { "未知" }

    private fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(command)
        val text = try {
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            try { process.destroy() } catch (e: Exception) { }
        }
        return text
    }

    private fun conclusion(
        listenerOn: Boolean,
        connected: Boolean,
        allowedEmpty: Boolean,
        connectedAt: Long,
        probeState: Int,
        sessionCount: Int,
        filterPaused: Boolean,
        ruleCount: Int
    ): String {
        val sb = StringBuilder("【7. 自动诊断结论】\n")
        when {
            !listenerOn -> sb.appendLine(
                "通知使用权未授予 —— 这是最高频根因。请到 系统设置 → 通知/特殊应用权限 → 通知使用权，" +
                    "把小豆通知打开。注意它和「通知发送权限」不是一回事：前者才允许读取别人的通知。"
            )
            !connected -> sb.appendLine(
                "★假连接（本次确诊）：通知使用权已给，但监听服务没有真正连上系统，" +
                    "所以 onNotificationPosted 根本不会触发，自测当然捕获不到。" +
                    "旧版本用「服务实例存在」误判为已连接、且因此跳过重连，导致永远修不好。" +
                    "本版已修复：打开应用时、诊断页点「强制重连监听」都会真正重新绑定。请先点强制重连，" +
                    "再跑一次自测。vivo/三星等机型还需在 i管家/设置里放开「自启动」与「通知使用权」。"
            )
            connectedAt == 0L -> sb.appendLine(
                "监听服务刚连接但尚无历史连接记录，先跑一次自测观察结果。国产机型请放开自启动/后台运行。"
            )
            probeState == STATE_CAPTURED || sessionCount > 0 -> {
                sb.appendLine("监听链路正常：自测或真实通知已进入回调（捕获 $sessionCount 条）。如果仍觉得「没拦截」，问题在过滤层：")
                if (filterPaused) sb.appendLine("  · 你打开了「暂停过滤」——此时只记录不拦截，请关闭。")
                if (ruleCount <= 0) sb.appendLine("  · 没有任何启用的规则，且 AI 分数可能未达阈值，导致不动作。")
                sb.appendLine("  · 确认规则关键词与通知内容匹配；确认通知不在排除名单。")
                sb.appendLine("  · 注意：替换内容/改铃声等动作受系统限制本就不会生效，只有「消除通知(DISMISS)」能真正拦掉。")
            }
            allowedEmpty -> sb.appendLine(
                "★通知类型过滤器为空：监听服务已连上，但系统不允许把任何类型的通知分发给本应用，" +
                    "所以 onNotificationPosted 永不触发、历史始终为 0。旧版 Manifest 的 default_filter_types " +
                    "写错（逗号+非法值），系统首次安装时记住了空列表。v1.4.3 已改为官方格式，" +
                    "但系统只在安装时读取一次——撤销重授权往往不够。请卸载本应用后重新安装并重新授权，" +
                    "让系统按新配置生成通知类型列表。重装后再跑自测。"
            )
            else -> sb.appendLine(
                "★服务已连上但一条通知都收不到（自测/会话/历史全为 0）：权限与连接都显示正常，" +
                    "问题在系统没有把通知分发过来。请按顺序做三件事：① 打开系统「设置 → 通知使用权 → 小豆通知」，" +
                    "看清里面是否有「通知类型 / 允许查看的通知」子开关，如果有，把对话/提醒/静默全部打开；" +
                    "② 卸载本应用后重装（不是覆盖）——默认通知类型只在安装时按 Manifest 生成，覆盖不会刷新；" +
                    "③ 重装后重新授权并跑自测。若仍不行，请到系统「通知使用权」页截图发我，我看子开关状态。"
            )
        }
        return sb.toString()
    }
}
