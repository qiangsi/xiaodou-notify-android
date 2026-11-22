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
        // v1.7.6 待办收件箱入口：与历史页卡片共用同一个下钻通道
        binding.statTodoEntry.setOnClickListener {
            (activity as? MainActivity)?.openHistoryWithFilter(HistoryFragment.FILTER_TODO)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::app.isInitialized && _binding != null) refresh()
    }

    /** show/hide 复用模式下切回本页不触发 onResume，需在变为可见时刷新数据 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::app.isInitialized && _binding != null) refresh()
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

            // v1.7.6 待办入口：与历史页保持同样的可见口径（默认不含系统操作提示）
            // P1 性能：旧实现用 observeAll().first() 读取整张表再在内存过滤；
            // 改为按待办 id 集合分批查询最小投影，避免为数个 id 载入全表明文实体。
            val todoIds = app.prefs.todoIdSet()
            val todoCount = if (todoIds.isEmpty()) 0 else {
                todoIds.chunked(400).sumOf { batch ->
                    dao.categoryByIds(batch).count { row ->
                        showFeedback || row.category != com.xiaodou.notify.engine.FeedbackNoticeFilter.CATEGORY
                    }
                }
            }

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
                binding.tvStatTodoSub.text = if (todoCount > 0)
                    getString(R.string.todo_inbox_pending, todoCount)
                else getString(R.string.todo_inbox_empty)
                binding.tvStatTodoBadge.visibility = if (todoCount > 0) View.VISIBLE else View.GONE
                if (todoCount > 0) {
                    binding.tvStatTodoBadge.text = if (todoCount > 99) "99+" else todoCount.toString()
                }
                binding.statTodoEntry.alpha = if (todoCount > 0) 1f else 0.72f
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
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = app.database.notificationDao()
            val showFeedback = app.prefs.showFeedbackNotices
            val since = System.currentTimeMillis() - 7 * 86_400_000L
            val total = dao.countSinceVisible(since, showFeedback)
            val filtered = dao.countFilteredSinceVisible(since, showFeedback)
            val top = dao.packageRankVisible(since, showFeedback).firstOrNull()?.packageName
            val topLabel = top?.let {
                try {
                    app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(it, 0)).toString()
                } catch (e: Exception) { it }
            } ?: ""
            withContext(Dispatchers.Main) { showWeeklyCard(total, filtered, topLabel) }
        }
    }

    private fun showWeeklyCard(total: Int, filtered: Int, top: String) {
        if (total <= 0) {
            android.widget.Toast.makeText(requireContext(), R.string.weekly_empty, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val ratio = filtered.toFloat() / total
        val score = (60 + ratio * 39).toInt().coerceIn(60, 99)
        val data = com.xiaodou.notify.util.WeeklyCard.Data(
            filtered = filtered, total = total, score = score,
            topApp = top.ifBlank { getString(R.string.app_name) },
            title = getString(R.string.weekly_title),
            subtitle = getString(R.string.weekly_subtitle),
            footer = getString(R.string.weekly_footer)
        )
        val bmp = com.xiaodou.notify.util.WeeklyCard.render(data)
        val uri = com.xiaodou.notify.util.WeeklyCard.saveAndShareUri(requireContext(), bmp)
        val view = android.widget.ImageView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 0) }
            adjustViewBounds = true
            setImageBitmap(bmp)
        }
        val box = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(view)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weekly_title)
            .setView(box)
            .setPositiveButton(R.string.weekly_share) { _, _ ->
                runCatching {
                    startActivity(Intent.createChooser(
                        com.xiaodou.notify.util.WeeklyCard.shareIntent(requireContext(), uri),
                        getString(R.string.weekly_share)
                    ))
                }
            }
            .setNeutralButton(R.string.weekly_save) { _, _ -> saveWeeklyToGallery(bmp) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveWeeklyToGallery(bmp: android.graphics.Bitmap) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "qingjing_${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/XiaodouNotify")
            }
        }
        val resolver = requireContext().contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            android.widget.Toast.makeText(requireContext(), R.string.weekly_save_fail, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.widget.Toast.makeText(requireContext(), R.string.weekly_save_done, android.widget.Toast.LENGTH_SHORT).show()
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
