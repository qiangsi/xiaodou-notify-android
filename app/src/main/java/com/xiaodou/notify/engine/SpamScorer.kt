package com.xiaodou.notify.engine

import com.xiaodou.notify.data.AiFeedback
import kotlin.math.roundToInt

/**
 * 离线骚扰打分器（本地启发式 + 标注学习）。
 * 完全在设备本地运行，不联网、不上传。可被真正的 TFLite 模型替换。
 *
 * 打分来源：
 *  1) 内置营销/骚扰关键词权重；
 *  2) 用户对同源通知标注（是骚扰/正常）形成的 App 偏置；
 *  3) 垃圾样本中的高频词。
 */
object SpamScorer {

    private val SPAM_WORDS = listOf(
        "免费", "领取", "红包", "优惠", "折扣", "特价", "秒杀", "抢购", "限时", "仅剩",
        "中奖", "一等奖", "奖金", "现金", "返现", "返利", "佣金", "兼职", "日入", "躺赚",
        "贷款", "额度", "借款", "分期", "低息", "信用卡", "额度提升", "提额",
        "发票", "代开", "中奖", "恭喜", "点击", "立即", "马上", "速来", "不要再错过",
        "推广", "广告", "营销", "促销", "爆款", "清仓", "甩卖", "团购", "拼团", "砍价",
        "投资", "炒股", "荐股", "博彩", "彩票", "赌", "成人", "约会", "美女"
    )

    private val NORMAL_HINT = listOf("验证码", "快递", "已发货", "签收", "取件码", "到账", "转账")

    data class Model(val appSpamBias: Map<String, Int>, val spamTokens: Set<String>)

    /** 从标注样本构建轻量模型（在充电/闲置时调用也可） */
    fun buildModel(feedback: List<AiFeedback>, decrypt: (String) -> String): Model {
        val spamByApp = mutableMapOf<String, Int>()
        val normalByApp = mutableMapOf<String, Int>()
        val tokens = mutableSetOf<String>()
        feedback.forEach { f ->
            if (f.label == 1) {
                spamByApp[f.packageName] = (spamByApp[f.packageName] ?: 0) + 1
                tokens += tokenize(decrypt(f.textEnc))
            } else {
                normalByApp[f.packageName] = (normalByApp[f.packageName] ?: 0) + 1
            }
        }
        val bias = mutableMapOf<String, Int>()
        (spamByApp.keys + normalByApp.keys).forEach { pkg ->
            val s = spamByApp[pkg] ?: 0
            val n = normalByApp[pkg] ?: 0
            if (s + n >= 2) {
                bias[pkg] = ((s - n).toFloat() / (s + n) * 40).roundToInt()
            }
        }
        return Model(bias, tokens)
    }

    /** 返回 0~100 的骚扰概率 */
    fun score(title: String, text: String, packageName: String, model: Model): Int {
        val all = "$title $text"
        var s = 0
        // 命中营销词
        val hits = SPAM_WORDS.count { all.contains(it) }
        s += (hits * 18).coerceAtMost(70)
        // 命中用户 spam 样本高频词
        val learnedHits = model.spamTokens.count { token -> token.length >= 2 && all.contains(token) }
        s += (learnedHits * 8).coerceAtMost(20)
        // App 偏置
        s += model.appSpamBias[packageName] ?: 0
        // 正常提示词降权
        if (NORMAL_HINT.any { all.contains(it) }) s -= 30
        // 纯数字短文本（验证码特征）降权
        if (all.trim().length <= 8 && all.trim().all { it.isDigit() || it.isLetter() }) s -= 20
        return s.coerceIn(0, 100)
    }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9]+")).filter { it.length in 2..8 }.take(20)
}
