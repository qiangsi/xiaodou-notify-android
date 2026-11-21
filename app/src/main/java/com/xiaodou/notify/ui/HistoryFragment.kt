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
import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

        binding.chipFilter.setOnCheckedChangeListener { _, checkedId ->
            filter = when (checkedId) {
                R.id.chip_kept -> FILTER_KEPT
                R.id.chip_filtered -> FILTER_FILTERED
                R.id.chip_learned -> FILTER_LEARNED
                else -> FILTER_ALL
            }
            applyList()
        }

        // 统计页数字跳转：按传入的初始维度选中对应 chip（singleSelection 会自动取消其它）
        when (arguments?.getInt(ARG_FILTER, FILTER_ALL) ?: FILTER_ALL) {
            FILTER_KEPT -> binding.chipKept.isChecked = true
            FILTER_FILTERED -> binding.chipFiltered.isChecked = true
            FILTER_LEARNED -> binding.chipLearned.isChecked = true
            else -> binding.chipAll.isChecked = true
        }
        // 「已学习样本」在最右侧，确保跳转过来时能看见被选中的 chip
        if (filter == FILTER_LEARNED) {
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
                all = list
                applyList()
            }
        }
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

    private fun applyList() {
        // v1.7.3：系统操作提示默认隐藏，只有开关打开才进入历史列表与计数
        val showFeedback = app?.prefs?.showFeedbackNotices == true
        val scopeAll = if (showFeedback) all
        else all.filter { it.category != com.xiaodou.notify.engine.FeedbackNoticeFilter.CATEGORY }
        var list = scopeAll
        if (filter == FILTER_KEPT) list = list.filter { it.result == Result.KEPT }
        if (filter == FILTER_FILTERED) list = list.filter { it.result != Result.KEPT }
        if (filter == FILTER_LEARNED) list = list.filter { it.marked != null }
        if (selectedType != null) list = list.filter { it.category == selectedType }
        if (selectedDay != null) list = list.filter {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp)) == selectedDay
        }
        if (keyword.isNotEmpty()) {
            val kw = keyword.lowercase()
            list = list.filter { item ->
                item.appName.lowercase().contains(kw) ||
                item.packageName.lowercase().contains(kw) ||
                CryptoHelper.decrypt(item.titleEnc).lowercase().contains(kw) ||
                CryptoHelper.decrypt(item.textEnc).lowercase().contains(kw) ||
                (item.filterReason ?: "").lowercase().contains(kw) // 按命中规则/原因搜索
            }
        }
        adapter.submitList(list, groupByDay = selectedDay == null)

        // 概览计数（FR-H7）：基于当前可见全量数据实时统计，默认不含被隐藏的系统操作提示
        val kept = scopeAll.count { it.result == Result.KEPT }
        val filtered = scopeAll.count { it.result != Result.KEPT }
        binding.tvCount.text = getString(
            R.string.history_count_summary, kept, filtered,
            scopeAll.count { it.marked != null }
        )

        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = if (selectedDay != null || selectedType != null ||
            keyword.isNotEmpty() || filter != FILTER_ALL)
            getString(R.string.history_no_result) else getString(R.string.empty_history)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
