package com.xiaodou.notify.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.xiaodou.notify.XiaodouApp
import java.lang.ref.WeakReference

/**
 * 应用锁的全局生命周期守卫。
 *
 * 职责边界（v1.4.0 重构）：
 * - 只负责采集「离开/回来」的生命周期事件，并记录离开时刻；
 * - 「该不该验证」完全委托给 [AppLockPolicy]；
 * - 「怎么验证」完全委托给 [AppLockHelper]。
 * 三者解耦，离开时长、解锁方式的变化都不需要改动本类。
 */
object AppLockGuard {

    private var resumedCount = 0
    private var current: WeakReference<FragmentActivity>? = null
    private var prompting = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由 XiaodouApp 在 Application.onCreate 注册 */
    fun install(context: Context) {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) onLeaveApp()
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun onActivityResumed(activity: Activity) {
        if (activity is FragmentActivity) current = WeakReference(activity)
        resumedCount++
        // 生命周期回调触发时状态可能还没切到 RESUMED，延后一帧再判断，避免冷启动漏验证
        mainHandler.post { maybePrompt() }
    }

    fun onActivityPaused(@Suppress("UNUSED_PARAMETER") activity: Activity) {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
    }

    fun onActivityStopped() {
        // 所有页面都停下来才算真正退到后台；页面间跳转时 resumedCount 仍为 1
        if (resumedCount == 0) {
            if (prompting) {
                // 弹窗还没结论就离开：这是「未完成验证」的离开，
                // 不能当普通退后台享受宽限期，下次进入必须重新验证
                try {
                    AppLockPolicy.forceLock(XiaodouApp.instance.prefs)
                } catch (e: Exception) {
                    // Application 尚未初始化完成，忽略
                }
            } else {
                onLeaveApp()
            }
            // 兜底：页面全部停止时验证弹窗已不可能有效，清除防重入标志。
            // 否则弹窗被系统/Activity 重建销毁而没有回调时，prompting 会永久卡 true，
            // 之后所有验证请求都被 maybePrompt 的 if (prompting) return 吞掉。
            prompting = false
        }
    }

    /** 离开应用：记录离开时刻，供策略按配置时长判定 */
    private fun onLeaveApp() {
        try {
            AppLockPolicy.markBackground(XiaodouApp.instance.prefs)
        } catch (e: Exception) {
            // Application 尚未初始化完成，忽略
        }
    }

    /** 设置页点击「立即锁定」：强制下次进入验证 */
    fun lockNow() {
        val prefs = XiaodouApp.instance.prefs
        if (!prefs.appLockEnabled) return
        AppLockPolicy.forceLock(prefs)
        prompting = false
        mainHandler.post { maybePrompt() }
    }

    /** 验证成功或关闭应用锁时调用 */
    fun unlock() {
        prompting = false
    }

    private fun maybePrompt() {
        val activity = current?.get() ?: return
        if (prompting) return
        val prefs = XiaodouApp.instance.prefs
        if (!AppLockPolicy.needsVerification(prefs)) {
            // 未超时或应用锁关闭：不打扰
            return
        }
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        prompting = true
        AppLockHelper.prompt(activity, prefs,
            onSuccess = {
                AppLockPolicy.markVerified(prefs)
                prompting = false
            },
            onFail = {
                prompting = false
                // 失败/取消后立即置为「下次进入必须验证」，不能只靠 moveTaskToBack
                // 的 onStop 间接记录离开——那样会受离开时长宽限期影响而放行。
                AppLockPolicy.forceLock(prefs)
                // 不 finish 主页，否则任务栈会被销毁；退回后台即可，回来会再次要求验证
                try {
                    activity.moveTaskToBack(true)
                } catch (e: Exception) {
                    activity.finish()
                }
            }
        )
    }
}
