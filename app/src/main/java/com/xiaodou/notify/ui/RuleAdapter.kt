package com.xiaodou.notify.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.FilterRule
import com.xiaodou.notify.databinding.ItemRuleBinding

class RuleAdapter(
    private val onEdit: (FilterRule) -> Unit,
    private val onToggle: (FilterRule, Boolean) -> Unit,
    private val onDelete: (FilterRule) -> Unit,
    private val onMove: (FilterRule, Int) -> Unit, // delta: -1 上移, +1 下移
    private val onDuplicate: (FilterRule) -> Unit
) : ListAdapter<FilterRule, RuleAdapter.VH>(Diff()) {

    inner class VH(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)

    class Diff : DiffUtil.ItemCallback<FilterRule>() {
        override fun areItemsTheSame(a: FilterRule, b: FilterRule) = a.id == b.id
        override fun areContentsTheSame(a: FilterRule, b: FilterRule) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rule = getItem(position)
        val b = holder.binding
        b.tvName.text = rule.name
        b.tvDesc.text = buildDesc(rule)
        b.switchEnabled.setOnCheckedChangeListener(null)
        b.switchEnabled.isChecked = rule.enabled
        b.switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
        b.root.setOnClickListener { onEdit(rule) }
        b.btnDelete.setOnClickListener { onDelete(rule) }
        b.btnUp.setOnClickListener { onMove(rule, -1) }
        b.btnDown.setOnClickListener { onMove(rule, 1) }
        b.btnDup.setOnClickListener { onDuplicate(rule) }
        // 边界：首条禁用上移、末条禁用下移
        b.btnUp.isEnabled = position > 0
        b.btnDown.isEnabled = position < itemCount - 1
        b.btnUp.alpha = if (b.btnUp.isEnabled) 1f else 0.3f
        b.btnDown.alpha = if (b.btnDown.isEnabled) 1f else 0.3f
    }

    private fun buildDesc(r: FilterRule): String {
        val parts = mutableListOf<String>()
        if (r.packageNames.isNotBlank()) parts += "App: ${r.packageNames.split(",").size}个"
        else parts += "全部应用"
        if (r.keywordInclude.isNotBlank()) parts += "含「${r.keywordInclude}」"
        if (r.timeStartMin >= 0) parts += "定时段"
        if (r.daysOfWeek.isNotBlank()) parts += "指定星期"
        if (r.chargingOnly) parts += "仅充电"
        if (r.screenOffOnly) parts += "仅锁屏"
        parts += "→ ${actionName(r.action)}"
        return parts.joinToString(" · ")
    }

    private fun actionName(action: String) = when (action) {
        Actions.KEEP -> "保留"
        Actions.DISMISS -> "消除"
        Actions.MUTE -> "静音"
        Actions.SNOOZE -> "推迟"
        Actions.REPLACE -> "替换"
        Actions.RINGTONE -> "铃声"
        Actions.TTS -> "朗读"
        Actions.CALL_UI -> "来电提醒"
        Actions.TAP_BUTTON -> "点击按钮"
        Actions.OPEN -> "打开"
        Actions.WEBHOOK -> "Webhook"
        else -> action
    }
}
