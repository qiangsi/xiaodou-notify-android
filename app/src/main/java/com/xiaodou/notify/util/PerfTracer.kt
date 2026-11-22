package com.xiaodou.notify.util

import android.os.Trace
import android.util.Log

/**
 * 轻量性能打点：默认关闭，零开销；仅在诊断页打开开关后，
 * 把关键链路耗时同时写入 logcat（tag=XiaodouPerf）与系统 Trace（可用 Perfetto 抓取）。
 *
 * 用途：跨品牌设备实测「切页 / inflate / 列表重算」耗时，对照优化验收标准。
 * 打点本身不采集任何通知内容，只记录阶段名与耗时毫秒。
 */
object PerfTracer {

    const val LOG_TAG = "XiaodouPerf"

    /** 由 XiaodouApp.onCreate 从 Prefs 同步；诊断页切换开关后即时生效 */
    @Volatile
    var enabled: Boolean = false

    inline fun section(name: String, block: () -> Unit) {
        if (!enabled) {
            block()
            return
        }
        val start = System.nanoTime()
        Trace.beginSection(name)
        try {
            block()
        } finally {
            Trace.endSection()
            Log.d(LOG_TAG, "$name ${String.format("%.1f", (System.nanoTime() - start) / 1_000_000f)} ms")
        }
    }

    /** 记录一个瞬时事件（如 Flow 重发次数），仅在开启时输出 */
    fun event(name: String, detail: String = "") {
        if (enabled) Log.d(LOG_TAG, if (detail.isEmpty()) name else "$name $detail")
    }
}
