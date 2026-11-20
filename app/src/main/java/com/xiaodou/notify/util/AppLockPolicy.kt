package com.xiaodou.notify.util

/**
 * 应用锁「何时需要验证」的纯策略，v1.4.0。
 *
 * 职责边界：只根据 [Prefs] 里记录的离开时刻与用户配置的离开时长，
 * 判断重新进入时是否要弹出验证。不持有 Activity、不弹窗、不依赖生命周期，
 * 因此可被 AppLockGuard 复用，也可单独单元测试。
 *
 * 语义：
 * - 离开应用（退后台 / 锁屏 / 主动锁定）时记录离开时刻；
 * - 重新进入时，若离开时长 >= 阈值则验证，未达阈值不打扰（如快速切换 App）；
 * - 阈值 0 表示每次进入都验证（旧行为）；
 * - 验证成功后清除离开记录，避免同一次离开被反复验证。
 */
object AppLockPolicy {

    /**
     * 进程内「强制下次进入必须验证」标记。
     * 验证失败/取消后立即置位；它优先于离开时长宽限期，且不会被随后的
     * markBackground 用新的离开时刻覆盖——否则用户失败后立刻返回会落在
     * 宽限期内被直接放行。进程重启后该标记丢失，但 forceLock 已写入足够早的
     * lastBackgroundAt，needsVerification 仍成立，不会因此失守。
     */
    private var forceLocked = false

    /** 退到后台或锁屏时调用，标记一次「待验证的离开」。 */
    fun markBackground(prefs: Prefs, now: Long = System.currentTimeMillis()) {
        if (!prefs.appLockEnabled) return
        // 已强制锁定时保留更早的离开时刻，避免宽限期重新生效
        if (forceLocked) return
        prefs.lastBackgroundAt = now
    }

    /**
     * 重新进入时是否需要验证。
     * 返回 false 的两种情况：应用锁未开启；或没有待验证的离开记录（本次会话从未离开）。
     */
    fun needsVerification(prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.appLockEnabled) return false
        if (forceLocked) return true
        val leftAt = prefs.lastBackgroundAt
        if (leftAt <= 0L) return false
        val awayMs = (now - leftAt).coerceAtLeast(0L)
        return awayMs >= prefs.appLockAwayMs
    }

    /** 验证成功：清除离开记录并刷新解锁时刻。 */
    fun markVerified(prefs: Prefs, now: Long = System.currentTimeMillis()) {
        forceLocked = false
        prefs.lastBackgroundAt = 0L
        prefs.lastUnlockAt = now
    }

    /**
     * 「立即锁定」/「验证失败」：强制下次进入必须验证，与离开时长配置解耦。
     */
    fun forceLock(prefs: Prefs, now: Long = System.currentTimeMillis()) {
        forceLocked = true
        prefs.lastBackgroundAt = now - prefs.appLockAwayMs.coerceAtLeast(0L)
    }

    /** 关闭应用锁 / 重置：清除待验证状态。 */
    fun clear(prefs: Prefs) {
        forceLocked = false
        prefs.lastBackgroundAt = 0L
    }
}
