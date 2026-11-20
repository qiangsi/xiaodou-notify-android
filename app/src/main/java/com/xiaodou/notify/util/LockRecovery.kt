package com.xiaodou.notify.util

import com.xiaodou.notify.R

/**
 * 应用锁备用恢复（v1.4.0）。
 *
 * 目的：用户忘记 PIN 时，凭预先设置的安全问题答案重置 PIN，避免启用应用锁后被永久锁在门外。
 * 与 [PinHelper] 同样只保存「盐 + SHA-256(盐+归一化答案)」，绝不存明文答案。
 * 这是纯数据层工具，UI（题型选择、输入框）由设置页负责。
 */
object LockRecovery {

    /** 预设安全问题，索引与字符串资源一一对应 */
    val QUESTIONS = intArrayOf(
        R.string.recovery_q1,
        R.string.recovery_q2,
        R.string.recovery_q3,
        R.string.recovery_q4
    )

    fun hasRecovery(prefs: Prefs): Boolean =
        prefs.recoveryQuestionIndex in QUESTIONS.indices &&
            prefs.recoveryHash.isNotBlank() && prefs.recoverySalt.isNotBlank()

    fun setRecovery(prefs: Prefs, questionIndex: Int, answer: String) {
        require(questionIndex in QUESTIONS.indices) { "invalid question index" }
        require(answer.trim().length >= 2) { "answer too short" }
        if (prefs.recoverySalt.isBlank()) prefs.recoverySalt = PinHelper.newSalt()
        prefs.recoveryQuestionIndex = questionIndex
        prefs.recoveryHash = PinHelper.hash(normalize(answer), prefs.recoverySalt)
    }

    fun verify(prefs: Prefs, answer: String): Boolean {
        if (!hasRecovery(prefs)) return false
        return PinHelper.hash(normalize(answer), prefs.recoverySalt) == prefs.recoveryHash
    }

    fun clear(prefs: Prefs) {
        prefs.recoveryQuestionIndex = -1
        prefs.recoveryHash = ""
        prefs.recoverySalt = ""
    }

    /** 归一化：去首尾空白并忽略大小写，提升答案容错 */
    private fun normalize(answer: String): String = answer.trim().lowercase()
}
