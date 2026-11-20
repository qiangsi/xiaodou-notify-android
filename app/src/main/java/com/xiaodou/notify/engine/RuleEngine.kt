package com.xiaodou.notify.engine

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.xiaodou.notify.data.FilterRule
import java.util.Calendar

/** 规则匹配上下文 */
data class MatchContext(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val charging: Boolean,
    val screenOff: Boolean
)

/** 规则条件匹配结果 */
data class MatchResult(val rule: FilterRule, val reason: String)

object RuleEngine {

    /** 按优先级顺序返回第一条命中的规则（首条命中即执行，PRD FR-R1 策略） */
    fun firstMatch(rules: List<FilterRule>, ctx: MatchContext): MatchResult? {
        for (rule in rules) {
            val reason = matches(rule, ctx) ?: continue
            return MatchResult(rule, reason)
        }
        return null
    }

    /** 返回所有命中规则，用于测试/调试 */
    fun allMatches(rules: List<FilterRule>, ctx: MatchContext): List<MatchResult> =
        rules.mapNotNull { rule -> matches(rule, ctx)?.let { MatchResult(rule, it) } }

    private fun matches(rule: FilterRule, ctx: MatchContext): String? {
        if (!rule.enabled) return null

        // App 维度
        if (rule.packageNames.isNotBlank()) {
            val pkgs = rule.packageNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (ctx.packageName !in pkgs) return null
        }

        // 关键字：排除优先
        val excludes = splitWords(rule.keywordExclude)
        if (excludes.any { ctx.title.contains(it, true) || ctx.text.contains(it, true) }) return null
        // 包含任一
        val includes = splitWords(rule.keywordInclude)
        if (includes.isNotEmpty() && includes.none { ctx.title.contains(it, true) || ctx.text.contains(it, true) }) return null

        // 时间段（支持跨零点）
        if (rule.timeStartMin >= 0 && rule.timeEndMin >= 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = ctx.timestamp }
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val inWindow = if (rule.timeStartMin <= rule.timeEndMin) {
                now in rule.timeStartMin..rule.timeEndMin
            } else {
                now >= rule.timeStartMin || now <= rule.timeEndMin
            }
            if (!inWindow) return null
        }

        // 星期
        if (rule.daysOfWeek.isNotBlank()) {
            val days = rule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
            val cal = Calendar.getInstance().apply { timeInMillis = ctx.timestamp }
            if (cal.get(Calendar.DAY_OF_WEEK) !in days) return null
        }

        if (rule.chargingOnly && !ctx.charging) return null
        if (rule.screenOffOnly && !ctx.screenOff) return null

        return "命中规则「${rule.name}」"
    }

    private fun splitWords(s: String): List<String> =
        s.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }

    fun deviceState(context: Context): Pair<Boolean, Boolean> {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val charging = battery?.isCharging == true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOff = pm?.isInteractive == false
        return charging to screenOff
    }
}
