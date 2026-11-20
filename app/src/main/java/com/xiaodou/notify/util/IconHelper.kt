package com.xiaodou.notify.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** v1.5.0 可切换桌面图标：通过 activity-alias 的启用状态切换桌面入口图标。 */
object IconHelper {

    val ICON_NAMES = arrayOf("经典", "极简铃铛", "小豆荚", "过滤漏斗", "信封勾选")

    /** 与 Manifest 中 5 个 activity-alias 一一对应，顺序即 Prefs.launcherIcon 索引 */
    private val ALIASES = arrayOf(
        "com.xiaodou.notify.ui.MainActivityAliasClassic",
        "com.xiaodou.notify.ui.MainActivityAliasBell",
        "com.xiaodou.notify.ui.MainActivityAliasPod",
        "com.xiaodou.notify.ui.MainActivityAliasFunnel",
        "com.xiaodou.notify.ui.MainActivityAliasMail"
    )

    const val ICON_COUNT = 5

    /**
     * 切换桌面图标。先启用目标、再停用其余，避免中间态桌面完全没有图标。
     * @return true=切换成功
     */
    fun switchIcon(context: Context, target: Int): Boolean {
        val index = target.coerceIn(0 until ICON_COUNT)
        val pm = context.applicationContext.packageManager
        return try {
            // 第一步：确保目标已启用（若其当前被禁用）
            pm.setComponentEnabledSetting(
                ComponentName(context.applicationContext, ALIASES[index]),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // 第二步：停用其它别名
            for (i in ALIASES.indices) {
                if (i == index) continue
                pm.setComponentEnabledSetting(
                    ComponentName(context.applicationContext, ALIASES[i]),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 应用启动时校正一次：确保只有 Prefs 记录的那个别名处于启用状态。 */
    fun ensureConsistent(context: Context, prefs: Prefs) {
        switchIcon(context, prefs.launcherIcon)
    }
}
