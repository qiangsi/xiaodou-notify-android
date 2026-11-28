package com.xiaodou.notify

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.AppDatabase
import com.xiaodou.notify.data.FilterRule
import com.xiaodou.notify.listener.KeepAliveJobService
import com.xiaodou.notify.util.AppLockGuard
import com.xiaodou.notify.util.Prefs
import com.xiaodou.notify.util.PerfTracer
import com.xiaodou.notify.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XiaodouApp : Application() {

    /** 应用级作用域，用于后台保存通知等无需绑定 UI 生命周期的任务 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val prefs: Prefs by lazy { Prefs(this) }


    override fun onCreate() {
        super.onCreate()
        instance = this
        // v1.7.15：铃声黑匣子先装好，再执行迁移——这样连「本 App 恢复铃声」这一动作
        // 也会被抓进黑匣子并标记为「本App」，用于自证：本 App 只恢复、从不静音。
        com.xiaodou.notify.engine.RingerTrace.install(this)
        // v1.7.13：旧版全局静音遗留状态一次性收尾。必须在任何通知处理前完成，
        // 把从 v1.7.1~v1.7.11 升级上来、仍停在静音的手机恢复为响铃并清除旧标记。
        // 只执行一次，之后本 App 永不再写系统铃声。
        com.xiaodou.notify.engine.LegacyMuteMigrator.migrateIfNeeded(this, prefs)
        // 性能打点：默认关闭；仅在诊断页手动打开过才启用，重启后按持久化状态恢复
        PerfTracer.enabled = prefs.perfTrace
        // FR-E6：深浅模式全局应用（配色由各 Activity 在 onCreate 前 setTheme）
        ThemeHelper.applyNightMode(prefs.themeMode)
        createChannels()

        // FR-E5：全局应用锁（所有页面统一拦截，退后台/锁屏即上锁）
        AppLockGuard.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = AppLockGuard.onActivityResumed(activity)
            override fun onActivityPaused(activity: Activity) = AppLockGuard.onActivityPaused(activity)
            override fun onActivityStopped(activity: Activity) = AppLockGuard.onActivityStopped()
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // 保活看门狗：任务被划掉或进程被回收后仍可恢复前台服务
        KeepAliveJobService.schedule(this)

        // 首次安装种入开箱即用的默认规则：不这样做的话，新用户装上看不到任何拦截效果
        seedDefaultRulesIfNeeded()

    }

    /**
     * 只在首次安装、且规则表为空时写入一组保守的默认规则。
     * 验证码保护优先级最高，确保重要验证码不被营销规则误伤。
     */
    private fun seedDefaultRulesIfNeeded() {
        if (prefs.defaultRulesSeeded) return
        appScope.launch {
            withContext(Dispatchers.IO) {
                val dao = database.ruleDao()
                if (dao.enabledRules().isEmpty()) {
                    dao.insert(FilterRule(
                        name = "保护验证码（不拦截）",
                        keywordInclude = "验证码,校验码,动态码,取件码,取件,登录码",
                        action = Actions.KEEP,
                        priority = 100
                    ))
                    dao.insert(FilterRule(
                        name = "拦截营销广告",
                        keywordInclude = "广告,推广,促销,秒杀,特价,优惠券,清仓,甩卖,全网最低,点击购买",
                        keywordExclude = "验证码,取件码,到账,转账",
                        action = Actions.DISMISS,
                        priority = 50
                    ))
                    dao.insert(FilterRule(
                        name = "拦截贷款赌博骚扰",
                        keywordInclude = "贷款,额度,借款,低息,提额,博彩,彩票,赌博,兼职日入",
                        action = Actions.DISMISS,
                        priority = 40
                    ))
                }
                prefs.defaultRulesSeeded = true
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channels = listOf(
                NotificationChannel(CH_RESTORED, getString(R.string.channel_restored), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_KEEP_ALIVE, getString(R.string.channel_keep_alive), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_SNOOZED, getString(R.string.channel_snoozed), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_CALL_UI, getString(R.string.channel_call_ui), NotificationManager.IMPORTANCE_HIGH)
            )
            channels.forEach { nm.createNotificationChannel(it) }
        }
    }

    companion object {
        const val CH_RESTORED = "restored"
        const val CH_KEEP_ALIVE = "keep_alive"
        const val CH_SNOOZED = "snoozed"
        const val CH_CALL_UI = "call_ui"

        lateinit var instance: XiaodouApp
            private set
    }
}
