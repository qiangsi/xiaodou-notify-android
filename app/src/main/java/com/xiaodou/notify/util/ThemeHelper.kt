package com.xiaodou.notify.util

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.xiaodou.notify.R

/** FR-E6 多主题：深浅模式 + 多套配色的统一入口。 */
object ThemeHelper {

    const val MODE_FOLLOW = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    val COLOR_NAMES = arrayOf(
        "经典蓝", "青绿", "紫罗兰", "珊瑚橙", "燕麦", "藕荷", "藏青"
    )
    val MODE_NAMES = arrayOf("跟随系统", "浅色", "深色")

    /** 配色总数，供设置页与索引裁剪共用，避免各处硬编码范围漂移 */
    const val COLOR_COUNT = 7

    /** 把 Prefs 的深浅模式应用到全局（Activity 重建后生效） */
    fun applyNightMode(mode: Int) {
        val delegateMode = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(delegateMode)
    }

    /** 根据配色索引返回主题 style 资源 id（必须在 setContentView 前 setTheme） */
    fun themeStyle(colorIndex: Int): Int = when (colorIndex.coerceIn(0 until COLOR_COUNT)) {
        1 -> R.style.Theme_XiaodouNotify_Teal
        2 -> R.style.Theme_XiaodouNotify_Purple
        3 -> R.style.Theme_XiaodouNotify_Coral
        4 -> R.style.Theme_XiaodouNotify_Oat
        5 -> R.style.Theme_XiaodouNotify_Lotus
        6 -> R.style.Theme_XiaodouNotify_Navy
        else -> R.style.Theme_XiaodouNotify
    }

    /**
     * 读取当前主题下的语义色。用于代码动态创建的 View（如统计图表进度条），
     * 避免写死颜色后在切换深浅模式或配色时出现错乱。
     */
    @ColorInt
    fun resolveColor(context: Context, @AttrRes attr: Int, @ColorRes fallback: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            a.getColor(0, ContextCompat.getColor(context, fallback))
        } finally {
            a.recycle()
        }
    }
}
