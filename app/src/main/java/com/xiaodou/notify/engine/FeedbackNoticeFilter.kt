package com.xiaodou.notify.engine

/**
 * 系统操作反馈通知识别（v1.7.3）：把「应用主动生成、并非用户明确要看」的即时提示单独归类，
 * 例如截图成功后弹出的「截图已保存」、开始录屏、投屏成功等。
 *
 * 设计原则（保守优先，宁可漏判也不误伤正常通知）：
 * 1. 只做「标记」，不取消系统通知栏里的任何通知 —— 本模块无权也不应替用户划掉系统通知。
 * 2. 默认隐藏的是本应用自己的「历史列表 / 通知中心计数」，原通知在系统通知栏的行为完全不变。
 * 3. 必须同时满足「来源可信」+「命中明确反馈词」才判定，普通 App 正文里偶尔提到"截图"不会误伤。
 * 4. 验证码 / 来电 / 媒体 / 常驻四类正常通知即使碰到关键词也直接豁免。
 *
 * category 取值见 [CATEGORY]；老数据 category 为 null 或其他值时行为不变。
 */
object FeedbackNoticeFilter {

    /** 落库 category 值：系统操作反馈（默认在历史/统计中隐藏，可通过设置开关显示） */
    const val CATEGORY = "feedback"

    /**
     * 来源白名单：系统 UI / 截屏服务 / 系统媒体服务。
     * 只在这些来源里才启用「通用反馈词」匹配；其他来源必须命中更强句式（见 [STRONG_PATTERNS]）。
     */
    private val SYSTEM_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.shell",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.miui.screenshot",
        "com.huawei.android.screenshot",
        "com.coloros.screenshots",
        "com.samsung.android.screenshot",
        "com.android.screenrecorder"
    )

    /**
     * 通用反馈词：来源命中白名单时，标题或正文含任意词即判定。
     * 注意都是「完成态」短语，避免正文里随口提一句"截图"就被归类。
     */
    private val FEEDBACK_WORDS = listOf(
        "截图已保存", "截屏已保存", "屏幕截图已保存", "已保存截图", "已截取屏幕", "屏幕已截取",
        "截屏成功", "截图成功", "屏幕已录制", "录屏已开始", "录屏已结束", "屏幕录制已开始",
        "屏幕录制已结束", "投屏已连接", "已开始投屏", "screenshot saved", "screen captured",
        "screen recording started", "screen recording stopped", "capture saved"
    )

    /**
     * 强句式：非白名单来源也判定，但要求「动作 + 完成态」同时出现，误判概率极低。
     * 例如第三方截图工具发出「截图已保存到相册」。
     */
    private val STRONG_PATTERNS = listOf(
        Regex("(截图|截屏|屏幕截图|录屏|屏幕录制).{0,8}(已保存|已截取|已生成|成功|已开始|已结束)", RegexOption.IGNORE_CASE),
        Regex("(screenshot|screen\\s*capture|screen\\s*recording).{0,24}(saved|captured|started|stopped)", RegexOption.IGNORE_CASE)
    )

    /** 这些类型属于用户明确需要查看的正常通知，无条件豁免 */
    private val EXEMPT_CATEGORIES = setOf("code", "call", "media", "ongoing")

    /**
     * 判定一条通知是否为「系统操作反馈」。
     *
     * @param packageName 通知来源包名
     * @param title 通知标题（解密后的明文）
     * @param text 通知正文（解密后的明文）
     * @param category 已算好的通知分类（categorize 结果）
     * @param ongoing 是否为常驻通知
     * @return true 表示应归类为 [CATEGORY]，默认在历史/统计中隐藏
     */
    fun isFeedbackNotice(
        packageName: String,
        title: String,
        text: String,
        category: String,
        ongoing: Boolean
    ): Boolean {
        if (ongoing) return false
        if (category in EXEMPT_CATEGORIES) return false

        val combined = "$title $text".lowercase()
        if (combined.isBlank()) return false

        val fromSystem = packageName in SYSTEM_PACKAGES
        if (fromSystem && FEEDBACK_WORDS.any { combined.contains(it.lowercase()) }) return true
        // 非白名单来源要求更强证据：动作词 + 完成态紧邻出现
        if (STRONG_PATTERNS.any { it.containsMatchIn(combined) }) return true
        return false
    }
}
