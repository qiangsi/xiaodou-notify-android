package com.xiaodou.notify.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.databinding.FragmentHistoryBinding
import com.xiaodou.notify.util.DecryptCache
import com.xiaodou.notify.util.PerfTracer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: NotificationAdapter
    private var all: List<com.xiaodou.notify.data.NotificationEntity> = emptyList()
    private var keyword = ""
    private var filter = FILTER_ALL
    private var searchJob: Job? = null
    /** 列表重算任务：新请求取消旧请求，避免搜索/筛选快速切换时旧结果覆盖新结果 */
    private var applyJob: Job? = null

    // 时间轴与多维筛选
    private var selectedDay: String? = null // yyyy-MM-dd
    private var selectedType: String? = null // code/ongoing/media/normal
    private var app: XiaodouApp? = null

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_KEPT = 1
        const val FILTER_FILTERED = 2
        /** 已学习样本：用户手动标注过（marked != null）的通知，用于回归 AI 学习成果 */
        const val FILTER_LEARNED = 3
        /** v1.7.5 待办收件箱：被推迟/手动加入的通知 */
        const val FILTER_TODO = 4

        private const val ARG_FILTER = "init_filter"

        /** 由统计页指标数字跳转进来时，带一个初始筛选维度 */
        fun newInstance(filter: Int = FILTER_ALL) = HistoryFragment().apply {
            arguments = Bundle().apply { putInt(ARG_FILTER, filter) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = requireActivity().application as XiaodouApp
        adapter = NotificationAdapter { item ->
            startActivity(Intent(requireContext(), NotificationDetailActivity::class.java).putExtra("id", item.id))
        }
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim() ?: ""
                debounceApply()
            }
        })

        // v1.7.6 待办收件箱首屏入口：复用统计页下钻的同一套筛选机制
        binding.cardTodoEntry.setOnClickListener {
            (activity as? MainActivity)?.openHistoryWithFilter(FILTER_TODO)
        }

        binding.chipFilter.setOnCheckedChangeListener { _, checkedId ->
            filter = when (checkedId) {
                R.id.chip_kept -> FILTER_KEPT
                R.id.chip_filtered -> FILTER_FILTERED
                R.id.chip_learned -> FILTER_LEARNED
                R.id.chip_todo -> FILTER_TODO
                else -> FILTER_ALL
            }
            applyList()
        }

        // 统计页数字跳转：按传入的初始维度选中对应 chip（singleSelection 会自动取消其它）
        when (arguments?.getInt(ARG_FILTER, FILTER_ALL) ?: FILTER_ALL) {
            FILTER_KEPT -> binding.chipKept.isChecked = true
            FILTER_FILTERED -> binding.chipFiltered.isChecked = true
            FILTER_LEARNED -> binding.chipLearned.isChecked = true
            FILTER_TODO -> binding.chipTodo.isChecked = true
            else -> binding.chipAll.isChecked = true
        }
        // 「已学习样本 / 待办」在右侧，确保跳转过来时能看见被选中的 chip
        if (filter == FILTER_LEARNED || filter == FILTER_TODO) {
            binding.filterScroll.post { binding.filterScroll.fullScroll(View.FOCUS_RIGHT) }
        }

        // 日期选择（FR-H3）
        binding.chipDate.setOnClickListener { showDatePicker() }
        binding.chipDate.setOnCloseIconClickListener {
            selectedDay = null
            binding.chipDate.text = getString(R.string.history_date_pick)
            applyList()
        }

        // 类型筛选（FR-H2）
        binding.chipType.setOnClickListener { showTypePicker() }
        binding.chipType.setOnCloseIconClickListener {
            selectedType = null
            binding.chipType.text = getString(R.string.search_type_all)
            applyList()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            app!!.database.notificationDao().observeAll().collectLatest { list ->
                PerfTracer.event("observeAll-emit", "rows=${list.size}")
                all = list
                applyList()
            }
        }
    }

    /**
     * 由统计页下钻调用（Fragment 被复用时不重建，故不能只靠 newInstance 的 arguments）。
     * 直接选中对应 chip 并触发重算；chip 的 checked 监听会改 filter 并 applyList。
     */
    fun applyIncomingFilter(newFilter: Int) {
        if (_binding == null) {
            arguments = Bundle().apply { putInt(ARG_FILTER, newFilter) }
            return
        }
        when (newFilter) {
            FILTER_KEPT -> binding.chipKept.isChecked = true
            FILTER_FILTERED -> binding.chipFiltered.isChecked = true
            FILTER_LEARNED -> binding.chipLearned.isChecked = true
            FILTER_TODO -> binding.chipTodo.isChecked = true
            else -> binding.chipAll.isChecked = true
        }
        if (newFilter == FILTER_LEARNED || newFilter == FILTER_TODO) {
            binding.filterScroll.post { binding.filterScroll.fullScroll(View.FOCUS_RIGHT) }
        }
        if (filter != newFilter) applyList()
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.history_date_pick)
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { utc ->
            // 用 UTC 日历取年月日，避免时区偏移导致错一天
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = utc
            selectedDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(utc))
            binding.chipDate.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            applyList()
        }
        picker.show(parentFragmentManager, "date_picker")
    }

    private fun showTypePicker() {
        val types = arrayOf(
            getString(R.string.search_type_all) to null,
            getString(R.string.search_type_code) to "code",
            getString(R.string.search_type_ongoing) to "ongoing",
            getString(R.string.search_type_media) to "media",
            getString(R.string.search_type_normal) to "normal",
            getString(R.string.search_type_feedback) to com.xiaodou.notify.engine.FeedbackNoticeFilter.CATEGORY
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_type_all)
            .setItems(types.map { it.first }.toTypedArray()) { _, which ->
                selectedType = types[which].second
                binding.chipType.text = types[which].first
                binding.chipType.isCloseIconVisible = selectedType != null
                applyList()
            }
            .show()
    }

    private fun debounceApply() {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(150)
            applyList()
        }
    }

    /**
     * 列表重算：筛选 / 解密 / 分组排序全部放到 Dispatchers.Default 后台线程，
     * 只把最终渲染结果切回主线程。P0-1 性能修复——旧实现在主线程逐条 filter + AES 解密，
     * 数据量大时切页与搜索必然掉帧。
     */
    private fun applyList() {
        applyJob?.cancel()
        // 捕获本次请求的参数快照，避免计算期间被后续交互改写
        val snapshot = all
        val currentFilter = filter
        val currentDay = selectedDay
        val currentType = selectedType
        val currentKeyword = keyword
        val showFeedback = app?.prefs?.showFeedbackNotices == true
        val todoIds = app?.prefs?.todoIdSet() ?: emptySet()

        applyJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            PerfTracer.event("applyList-start", "rows=${snapshot.size} filter=$currentFilter kw=${currentKeyword.length}")
            val t0 = System.nanoTime()

            // v1.7.3：系统操作提示默认隐藏，只有开关打开才进入历史列表与计数
            val scopeAll = if (showFeedback) snapshot
            else snapshot.filter { it.category != com.xiaodou.notify.engine.FeedbackNoticeFilter.CATEGORY }
            var list: List<NotificationEntity> = scopeAll
            if (currentFilter == FILTER_KEPT) list = list.filter { it.result == Result.KEPT }
            if (currentFilter == FILTER_FILTERED) list = list.filter { it.result != Result.KEPT }
            if (currentFilter == FILTER_LEARNED) list = list.filter { it.marked != null }
            if (currentFilter == FILTER_TODO) list = list.filter { todoIds.contains(it.id) }
            if (currentType != null) list = list.filter { it.category == currentType }
            if (currentDay != null) {
                // 后台线程内一次性创建 formatter，避免逐条 new（口径不变：本地时区 yyyy-MM-dd）
                val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                list = list.filter { dayFmt.format(Date(it.timestamp)) == currentDay }
            }
            if (currentKeyword.isNotEmpty()) {
                val kw = currentKeyword.lowercase()
                list = list.filter { item ->
                    item.appName.lowercase().contains(kw) ||
                    item.packageName.lowercase().contains(kw) ||
                    DecryptCache.plain(item).first.lowercase().contains(kw) ||
                    DecryptCache.plain(item).second.lowercase().contains(kw) ||
                    (item.filterReason ?: "").lowercase().contains(kw) // 按命中规则/原因搜索
                }
            }

            // 概览计数（FR-H7）：基于当前可见全量数据实时统计，默认不含被隐藏的系统操作提示
            val kept = scopeAll.count { it.result == Result.KEPT }
            val filtered = scopeAll.count { it.result != Result.KEPT }
            val learned = scopeAll.count { it.marked != null }
            val todoCount = scopeAll.count { todoIds.contains(it.id) }

            // 时间轴分组与 DiffUtil 差异计算同样在后台完成（原实现都在 Main 线程，数据量大时掉帧）
            val grouped = NotificationAdapter.group(list, groupByDay = currentDay == null)
            val diff = adapter.computeDiff(grouped)

            PerfTracer.event("applyList-compute", "${String.format("%.1f", (System.nanoTime() - t0) / 1_000_000f)} ms")

            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                adapter.applyDiff(grouped, diff)
                binding.tvCount.text = getString(
                    R.string.history_count_summary, kept, filtered, learned
                )
                // v1.7.6 待办入口：副文案与角标随待办数量实时变化，0 项时弱化显示
                binding.tvTodoEntrySub.text = if (todoCount > 0)
                    getString(R.string.todo_inbox_pending, todoCount)
                else getString(R.string.todo_inbox_empty)
                binding.tvTodoBadge.visibility = if (todoCount > 0) View.VISIBLE else View.GONE
                if (todoCount > 0) {
                    binding.tvTodoBadge.text = if (todoCount > 99) "99+" else todoCount.toString()
                }
                binding.cardTodoEntry.alpha = if (todoCount > 0) 1f else 0.72f

                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.text = if (currentDay != null || currentType != null ||
                    currentKeyword.isNotEmpty() || currentFilter != FILTER_ALL)
                    getString(R.string.history_no_result) else getString(R.string.empty_history)
            }
        }
    }

    override fun onDestroyView() {
        applyJob?.cancel()
        searchJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
