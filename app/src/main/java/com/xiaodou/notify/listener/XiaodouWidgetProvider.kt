package com.xiaodou.notify.listener

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.ui.MainActivity
import com.xiaodou.notify.util.ThemeHelper
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * FR-E4 桌面小部件：今日统计（总数/已过滤）+ 快捷入口（历史/暂停过滤）。
 * 数据定时刷新（系统 updatePeriodMillis）+ 收到通知/切换开关时主动刷新。
 */
class XiaodouWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val app = context.applicationContext as? XiaodouApp ?: return
                app.prefs.filterPaused = !app.prefs.filterPaused
                refreshAll(context)
            }
            ACTION_SCENE -> {
                val app = context.applicationContext as? XiaodouApp ?: return
                com.xiaodou.notify.engine.SceneController.next(context, app.prefs)
                refreshAll(context)
            }
            ACTION_REFRESH -> refreshAll(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.xiaodou.notify.widget.TOGGLE"
        const val ACTION_SCENE = "com.xiaodou.notify.widget.SCENE"
        const val ACTION_REFRESH = "com.xiaodou.notify.widget.REFRESH"

        /** 主动请求刷新所有小部件（通知落库后、设置变更后调用） */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, XiaodouWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, XiaodouWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send()
        }

        /** 按用户设置的深浅模式给 RemoteViews 着色，避免与 App 内主题不一致。 */
        private fun applyWidgetTheme(context: Context, views: RemoteViews, dark: Boolean, colorIndex: Int, sceneMode: Int) {
            views.setInt(
                R.id.widget_root, "setBackgroundResource",
                if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light
            )
            val text = ContextCompat.getColor(
                context, if (dark) R.color.widget_text_dark else R.color.widget_text_light
            )
            val subtext = ContextCompat.getColor(
                context, if (dark) R.color.widget_subtext_dark else R.color.widget_subtext_light
            )
            val accentRes = when (colorIndex.coerceIn(0 until ThemeHelper.COLOR_COUNT)) {
                1 -> if (dark) R.color.widget_accent_teal_dark else R.color.widget_accent_teal_light
                2 -> if (dark) R.color.widget_accent_purple_dark else R.color.widget_accent_purple_light
                3 -> if (dark) R.color.widget_accent_coral_dark else R.color.widget_accent_coral_light
                4 -> if (dark) R.color.widget_accent_oat_dark else R.color.widget_accent_oat_light
                5 -> if (dark) R.color.widget_accent_lotus_dark else R.color.widget_accent_lotus_light
                6 -> if (dark) R.color.widget_accent_navy_dark else R.color.widget_accent_navy_light
                else -> if (dark) R.color.widget_accent_dark else R.color.widget_accent_light
            }
            val accent = ContextCompat.getColor(context, accentRes)
            val btnBg = if (dark) R.drawable.widget_btn_bg_dark else R.drawable.widget_btn_bg_light
            views.setTextColor(R.id.widget_title, text)
            views.setTextColor(R.id.widget_today, accent)
            views.setTextColor(R.id.widget_today_label, subtext)
            views.setTextColor(R.id.widget_filtered, accent)
            views.setTextColor(R.id.widget_filtered_label, subtext)
            views.setTextColor(R.id.widget_btn_history, accent)
            views.setTextColor(R.id.widget_btn_toggle, accent)
            views.setTextColor(R.id.widget_btn_scene, accent)
            views.setInt(R.id.widget_btn_history, "setBackgroundResource", btnBg)
            views.setInt(R.id.widget_btn_toggle, "setBackgroundResource", btnBg)
            views.setInt(R.id.widget_btn_scene, "setBackgroundResource", btnBg)
            // v1.7.6 情景按钮：文案带 ▸ 提示可点，非默认场景用主色高亮
            views.setTextViewText(
                R.id.widget_btn_scene,
                context.getString(
                    R.string.widget_scene,
                    com.xiaodou.notify.engine.SceneController.name(context, sceneMode)
                )
            )
            views.setTextColor(
                R.id.widget_btn_scene,
                if (sceneMode != com.xiaodou.notify.engine.SceneController.OFF) accent else subtext
            )
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val app = context.applicationContext as? XiaodouApp ?: return
            val views = RemoteViews(context.packageName, R.layout.widget_xiaodou)
            // 小部件运行在桌面进程，无法直接跟随 App 内强制的深浅模式，这里按用户设置显式着色
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val dark = when (app.prefs.themeMode) {
                ThemeHelper.MODE_DARK -> true
                ThemeHelper.MODE_LIGHT -> false
                else -> systemDark
            }
            applyWidgetTheme(context, views, dark, app.prefs.themeColor, app.prefs.sceneMode)

            // 今日 00:00 起点
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val since = cal.timeInMillis

            app.appScope.launch {
                // v1.7.3：与历史/统计口径一致，默认隐藏系统操作提示
                val showFeedback = app.prefs.showFeedbackNotices
                val total = try { app.database.notificationDao().countSinceVisible(since, showFeedback) } catch (e: Exception) { 0 }
                val filtered = try { app.database.notificationDao().countFilteredSinceVisible(since, showFeedback) } catch (e: Exception) { 0 }
                val paused = app.prefs.filterPaused

                views.setTextViewText(R.id.widget_today, total.toString())
                views.setTextViewText(R.id.widget_filtered, filtered.toString())
                views.setTextViewText(
                    R.id.widget_btn_toggle,
                    context.getString(if (paused) R.string.widget_paused else R.string.widget_active)
                )
                // 情景按钮文案与高亮已在 applyWidgetTheme 中按当前场景设置

                // 点击整块 / 「历史」按钮 → 打开 App
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPi = PendingIntent.getActivity(
                    context, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openPi)
                views.setOnClickPendingIntent(R.id.widget_btn_history, openPi)

                // 「暂停/恢复过滤」按钮 → 广播切换
                val toggle = Intent(context, XiaodouWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
                val togglePi = PendingIntent.getBroadcast(
                    context, 2, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_toggle, togglePi)

                // 「情景模式」按钮 → 广播循环切换 常规→专注→睡眠→通勤
                val scene = Intent(context, XiaodouWidgetProvider::class.java).apply { action = ACTION_SCENE }
                val scenePi = PendingIntent.getBroadcast(
                    context, 3, scene,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_scene, scenePi)

                try { mgr.updateAppWidget(widgetId, views) } catch (e: Exception) { }
            }
        }
    }
}
