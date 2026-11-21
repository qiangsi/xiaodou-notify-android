package com.xiaodou.notify.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.DailyRaw
import com.xiaodou.notify.data.PackageCount
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.databinding.FragmentStatsBinding
import com.xiaodou.notify.util.ProHelper
import com.xiaodou.notify.util.ProSheet
import com.xiaodou.notify.util.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: XiaodouApp
    private var scope = SCOPE_ALL
    private var days = 7
    private var settingChip = false
    // v1.6.0 清净周报：留存最近一次统计结果，供分享卡复用
    private var lastTotal = 0
    private var lastFiltered = 0
    private var lastTopApp = ""

    companion object {
        private const val SCOPE_ALL = 0
        private const val SCOPE_KEPT = 1
        private const val SCOPE_FILTERED = 2
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = requireActivity().application as XiaodouApp

        binding.chipScope.setOnCheckedChangeListener { _, checkedId ->
            scope = when (checkedId) {
                R.id.chip_scope_kept -> SCOPE_KEPT
                R.id.chip_scope_filtered -> SCOPE_FILTERED
                else -> SCOPE_ALL
            }
            refresh()
        }
        binding.chipWindow.setOnCheckedChangeListener { _, checkedId ->
            if (settingChip) return@setOnCheckedChangeListener
            if (checkedId == R.id.chip_30d && !ProHelper.isActive(app.prefs)) {
                // v1.6.0：修复「点解锁没反应」——不再弹死 Toast，改为真正可激活的面板，
                // 且激活成功后立即兑现刚才被拦下的操作（自动切到 30 天）。
                settingChip = true
                binding.chip7d.isChecked = true
                settingChip = false
                days = 7
                ProSheet.gate(
                    requireContext(), app.prefs,
                    getString(R.string.pro_feature_stats_30d)
                ) {
                    settingChip = true
                    binding.chip30d.isChecked = true
                    settingChip = false
                    days = 30
                    refresh()
                }
            } else {
                days = if (checkedId == R.id.chip_30d) 30 else 7
            }
            refresh()
        }

        // v1.6.0 清净周报：战绩卡一键生成 + 分享（Pro）
        binding.btnWeekly.setOnClickListener {
            ProSheet.gate(requireContext(), app.prefs, getString(R.string.pro_feature_weekly)) {
                showWeeklyReport()
            }
        }

        // v1.6.1 指标数字可下钻：点数字直达历史页对应筛选，形成「统计 → 明细」闭环
        binding.statTotal.setOnClickListener {
            (activity as? MainActivity)?.openHistoryWithFilter(HistoryFragment.FILTER_ALL)
        }
        binding.statFiltered.setOnClickListener {
            (activity as? MainActivity)?.openHistoryWithFilter(HistoryFragment.FILTER_FILTERED)
        }
        binding.statLearned.setOnClickListener {
            (activity as? MainActivity)?.openHistoryWithFilter(HistoryFragment.FILTER_LEARNED)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::app.isInitialized && _binding != null) refresh()
    }

    private fun refresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = app.database.notificationDao()
            // v1.7.3：默认隐藏系统操作提示，统计口径与历史列表保持一致
            val showFeedback = app.prefs.showFeedbackNotices
            val total = dao.countAllVisible(showFeedback)
            val filtered = dao.countByResultVisible(Result.RULE, showFeedback) + dao.countByResultVisible(Result.AI, showFeedback)
            val since = System.currentTimeMillis() - days * 86_400_000L
            val raw = dao.dailyRawVisible(since, showFeedback) // timestamp + result
            val learned = dao.countLearnedVisible(showFeedback)

            // 排行：按口径取对应结果的应用分布（FR-S1 分口径查看）
            val rank = when (scope) {
                SCOPE_ALL -> dao.packageRankVisible(since, showFeedback)
                SCOPE_KEPT -> dao.packageRankByResultVisible(since, Result.KEPT, showFeedback)
                else -> {
                    val kept = dao.packageRankByResultVisible(since, Result.KEPT, showFeedback)
                    val filtered = dao.packageRankByResultVisible(since, Result.RULE, showFeedback) + dao.packageRankByResultVisible(since, Result.AI, showFeedback)
                    (kept + filtered).groupBy { it.packageName }
                        .map { PackageCount(it.key, it.value.sumOf { p -> p.cnt }) }
                        .sortedByDescending { it.cnt }
                }
            }
            val labels = rank.associate { row ->
                val label = try {
                    app.packageManager.getApplicationLabel(
                        app.packageManager.getApplicationInfo(row.packageName, 0)
                    ).toString()
                } catch (e: Exception) { row.packageName }
                row.packageName to label
            }

            // 日报趋势：按天聚合总数/过滤数（FR-S2）
            val dayFmt = SimpleDateFormat("MM-dd", Locale.getDefault())
            val cal = Calendar.getInstance()
            val trend = linkedMapOf<String, IntArray>() // label -> [total, filtered]
            // 初始化最近 days 天，保证无数据日也显示
            for (i in days - 1 downTo 0) {
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(Calendar.DAY_OF_YEAR, -i)
                trend[dayFmt.format(cal.time)] = intArrayOf(0, 0)
            }
            raw.forEach { r: DailyRaw ->
                val key = dayFmt.format(Date(r.timestamp))
                val arr = trend.getOrPut(key) { intArrayOf(0, 0) }
                arr[0] += 1
                if (r.result != Result.KEPT) arr[1] += 1
            }

            withContext(Dispatchers.Main) {
                binding.tvTotal.text = total.toString()
                binding.tvFiltered.text = filtered.toString()
                binding.tvLearned.text = learned.toString()
                // v1.6.0 留存周报素材
                lastTotal = total
                lastFiltered = filtered
                lastTopApp = rank.firstOrNull()?.let { labels[it.packageName] ?: it.packageName } ?: ""

                // 趋势条
                binding.dailyContainer.removeAllViews()
                val maxTrend = trend.values.maxOfOrNull { it[0] }?.coerceAtLeast(1) ?: 1
                trend.forEach { (day, arr) ->
                    val row = layoutInflater.inflate(R.layout.item_trend, binding.dailyContainer, false)
                    row.findViewById<TextView>(R.id.tv_day).text = day
                    row.findViewById<TextView>(R.id.tv_num).text = "${arr[0]} / ${arr[1]}"
                    row.findViewById<ProgressBar>(R.id.bar).apply {
                        max = maxTrend
                        progress = arr[0]
                        applyProgressTint(this)
                    }
                    binding.dailyContainer.addView(row)
                }

                // 排行
                binding.rankContainer.removeAllViews()
                binding.tvRankEmpty.visibility = if (rank.isEmpty()) View.VISIBLE else View.GONE
                val max = rank.firstOrNull()?.cnt ?: 1
                rank.take(10).forEach { row: PackageCount ->
                    val item = layoutInflater.inflate(R.layout.item_rank, binding.rankContainer, false)
                    item.findViewById<TextView>(R.id.tv_app).text = labels[row.packageName] ?: row.packageName
                    item.findViewById<TextView>(R.id.tv_count).text = "${row.cnt} 条"
                    item.findViewById<ProgressBar>(R.id.bar).apply {
                        this.max = max; progress = row.cnt
                        applyProgressTint(this)
                    }
                    // 点击排行直达规则创建（FR-S2）
                    item.setOnClickListener {
                        startActivity(Intent(requireContext(), RuleEditActivity::class.java).putExtra("pkg", row.packageName))
                    }
                    binding.rankContainer.addView(item)
                }
            }
        }
    }

    /** 统计图表进度条跟随当前主题配色，避免深色模式下出现系统默认灰色。 */
    private fun applyProgressTint(bar: ProgressBar) {
        val primary = ThemeHelper.resolveColor(
            requireContext(), androidx.appcompat.R.attr.colorPrimary, R.color.primary
        )
        bar.progressTintList = ColorStateList.valueOf(primary)
        bar.progressBackgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.surface_alt))
    }

    /**
     * v1.6.0 清净周报：把「过滤战绩」变成可炫耀、可分享的爽感内容。
     * 这类「情绪价值 + 可传播」的功能，比导出 CSV 之类事后查看型更有付费冲动。
     */
    private fun showWeeklyReport() {
        if (lastTotal <= 0) {
            android.widget.Toast.makeText(requireContext(), R.string.weekly_empty, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val ratio = if (lastTotal > 0) lastFiltered.toFloat() / lastTotal else 0f
        // 清净指数：过滤占比映射到 60~99，挡得越多越清净
        val score = (60 + ratio * 39).toInt().coerceIn(60, 99)
        val top = lastTopApp.ifBlank { getString(R.string.app_name) }
        val body = getString(R.string.weekly_body, lastFiltered, score, top)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weekly_title)
            .setMessage(body)
            .setPositiveButton(R.string.weekly_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "$body\n\n—— 来自小豆通知")
                }
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.weekly_share))) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun scopeMatch(result: String) = when (scope) {
        SCOPE_KEPT -> result == Result.KEPT
        SCOPE_FILTERED -> result != Result.KEPT
        else -> true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
