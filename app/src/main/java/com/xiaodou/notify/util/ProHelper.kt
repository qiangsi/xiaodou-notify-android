package com.xiaodou.notify.util

/**
 * 开源社区版占位实现（OSS stub）。
 *
 * 本项目采用 open-core 模式：功能代码开源，**离线授权内核与签发密钥不开源**。
 * 本文件在推送到公开仓库时替换真实实现，因此这里不含：
 *   - 任何签发密钥（明文或密文）
 *   - 激活码签名校验算法
 *   - 与签发脚本相关的任何信息
 *
 * 由此编译出的社区版是完整的免费版：所有免费功能可用，但 Pro 无法激活。
 * 需要 Pro 请使用官方发布的构建包，并向开发者索取激活码。
 *
 * 注：小豆通知是轻量独立版，其余通知核心与兄弟版一致。
 */
object ProHelper {

    data class ProInfo(
        val valid: Boolean,
        val forever: Boolean,
        val expireAt: Long,
        val typeLabel: String,
        val expired: Boolean = false
    )

    /** 社区版不含授权内核，任何激活码都无法校验通过。 */
    fun verify(code: String): ProInfo = ProInfo(false, false, 0L, "")

    /** 社区版恒为未激活。 */
    fun isActive(prefs: Prefs): Boolean = false

    /** 供设置页展示当前状态文字 */
    fun statusText(prefs: Prefs): String = "开源社区版"
}
