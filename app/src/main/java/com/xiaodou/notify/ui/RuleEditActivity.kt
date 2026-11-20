package com.xiaodou.notify.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.FilterRule
import com.xiaodou.notify.databinding.ActivityRuleEditBinding
import com.xiaodou.notify.util.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRuleEditBinding
    private lateinit var app: XiaodouApp
    private var editing: FilterRule? = null
    private var selectedPkgs = mutableListOf<String>()
    private var selectedAction = Actions.DISMISS
    private var pkgLabels = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.themeStyle((application as XiaodouApp).prefs.themeColor))
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as XiaodouApp

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupActionSpinner()

        binding.btnApps.setOnClickListener { showAppPicker() }

        val id = intent.getLongExtra("id", -1L)
        if (id > 0) {
            binding.toolbar.title = getString(R.string.rule_edit_title)
            lifecycleScope.launch {
                editing = withContext(Dispatchers.IO) { app.database.ruleDao().getById(id) }
                editing?.let { fill(it) }
            }
        } else {
            binding.toolbar.title = getString(R.string.rule_new_title)
            // 从分析页直达创建：预填来源 App
            intent.getStringExtra("pkg")?.let { pkg ->
                selectedPkgs.add(pkg)
                pkgLabels[pkg] = pkg
                updateAppsText()
            }
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun setupActionSpinner() {
        val labels = mapOf(
            Actions.KEEP to R.string.act_keep, Actions.DISMISS to R.string.act_dismiss,
            Actions.MUTE to R.string.act_mute, Actions.SNOOZE to R.string.act_snooze,
            Actions.REPLACE to R.string.act_replace, Actions.RINGTONE to R.string.act_ringtone,
            Actions.TTS to R.string.act_tts, Actions.CALL_UI to R.string.act_call_ui,
            Actions.TAP_BUTTON to R.string.act_tap_button, Actions.OPEN to R.string.act_open,
            Actions.WEBHOOK to R.string.act_webhook
        )
        val keys = Actions.ALL
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, keys.map { getString(labels[it]!!) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAction.adapter = adapter
        binding.spinnerAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedAction = keys[position]
                val needsParam = selectedAction in setOf(
                    Actions.SNOOZE, Actions.REPLACE, Actions.RINGTONE, Actions.MUTE, Actions.WEBHOOK
                )
                binding.etParam.visibility = if (needsParam) View.VISIBLE else View.GONE
                // Webhook 隐私明示（FR-R3-10）：明确告知数据将离开设备
                if (selectedAction == Actions.WEBHOOK) {
                    binding.tvWebhookNotice.visibility = View.VISIBLE
                    binding.etParam.hint = "https://example.com/hook"
                } else {
                    binding.tvWebhookNotice.visibility = View.GONE
                }
            }
        }
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }
        pkgLabels.clear()
        apps.forEach { pkgLabels[it.packageName] = pm.getApplicationLabel(it).toString() }
        val names = apps.map { pkgLabels[it.packageName]!! }.toTypedArray()
        val pkgs = apps.map { it.packageName }
        val checked = BooleanArray(apps.size) { idx -> selectedPkgs.contains(pkgs[idx]) }
        val selected = selectedPkgs.toMutableList()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_select_apps)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selected.add(pkgs[which]) else selected.remove(pkgs[which])
            }
            .setPositiveButton(R.string.action_ok) { _, _ ->
                selectedPkgs = selected.distinct().toMutableList()
                updateAppsText()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateAppsText() {
        binding.tvApps.text = if (selectedPkgs.isEmpty()) getString(R.string.rule_all_apps_hint)
        else if (selectedPkgs.size <= 3) selectedPkgs.joinToString(", ") { pkgLabels[it] ?: it }
        else "${selectedPkgs.size} 个应用"
    }

    private fun fill(rule: FilterRule) {
        binding.etName.setText(rule.name)
        selectedPkgs = rule.packageNames.split(",").filter { it.isNotBlank() }.toMutableList()
        updateAppsText()
        binding.etInclude.setText(rule.keywordInclude)
        binding.etExclude.setText(rule.keywordExclude)
        binding.etTimeStart.setText(rule.timeStartMin.takeIf { it >= 0 }?.let { fmtMin(it) } ?: "")
        binding.etTimeEnd.setText(rule.timeEndMin.takeIf { it >= 0 }?.let { fmtMin(it) } ?: "")
        // 恢复星期选择
        val days = rule.daysOfWeek.split(",").filter { it.isNotBlank() }.toSet()
        for (i in 0 until binding.chipDays.childCount) {
            val chip = binding.chipDays.getChildAt(i) as com.google.android.material.chip.Chip
            chip.isChecked = chip.tag?.toString() in days
        }
        binding.cbCharging.isChecked = rule.chargingOnly
        binding.cbScreenoff.isChecked = rule.screenOffOnly
        binding.etParam.setText(rule.actionParam)
        binding.cbEnabled.isChecked = rule.enabled
        val pos = Actions.ALL.indexOf(rule.action).coerceAtLeast(0)
        binding.spinnerAction.setSelection(pos)
    }

    private fun parseMin(s: String): Int {
        if (s.isBlank()) return -1
        val parts = s.split(":")
        return try {
            parts[0].toInt().coerceIn(0, 23) * 60 + parts[1].toInt().coerceIn(0, 59)
        } catch (e: Exception) { -1 }
    }

    private fun fmtMin(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

    private fun save() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入规则名称", Toast.LENGTH_SHORT).show()
            return
        }
        val startMin = parseMin(binding.etTimeStart.text.toString())
        val endMin = parseMin(binding.etTimeEnd.text.toString())
        // 边界校验：填写了时间但格式非法
        if ((binding.etTimeStart.text.toString().isNotBlank() && startMin < 0) ||
            (binding.etTimeEnd.text.toString().isNotBlank() && endMin < 0)) {
            Toast.makeText(this, "时间格式应为 HH:mm（如 18:00）", Toast.LENGTH_SHORT).show()
            return
        }
        if ((binding.etTimeStart.text.toString().isBlank()) != (binding.etTimeEnd.text.toString().isBlank())) {
            Toast.makeText(this, "开始与结束时间需同时填写", Toast.LENGTH_SHORT).show()
            return
        }
        val param = binding.etParam.text.toString().trim()
        // Webhook URL 校验（FR-R3-10 边界）
        if (selectedAction == Actions.WEBHOOK && param.isNotBlank()) {
            if (!param.startsWith("http://", true) && !param.startsWith("https://", true)) {
                Toast.makeText(this, "Webhook 地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                return
            }
        }
        // 推迟分钟数校验
        if (selectedAction == Actions.SNOOZE && param.isNotBlank()) {
            val m = param.toIntOrNull()
            if (m == null || m !in 1..1440) {
                Toast.makeText(this, "推迟分钟数需为 1~1440", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val rule = FilterRule(
            id = editing?.id ?: 0,
            name = name,
            enabled = binding.cbEnabled.isChecked,
            packageNames = selectedPkgs.joinToString(","),
            keywordInclude = binding.etInclude.text.toString().trim(),
            keywordExclude = binding.etExclude.text.toString().trim(),
            timeStartMin = startMin,
            timeEndMin = endMin,
            daysOfWeek = (0 until binding.chipDays.childCount).mapNotNull { i ->
                val chip = binding.chipDays.getChildAt(i) as com.google.android.material.chip.Chip
                if (chip.isChecked) chip.tag?.toString() else null
            }.joinToString(","),
            chargingOnly = binding.cbCharging.isChecked,
            screenOffOnly = binding.cbScreenoff.isChecked,
            action = selectedAction,
            actionParam = param,
            priority = editing?.priority ?: 0
        )
        lifecycleScope.launch(Dispatchers.IO) {
            if (editing != null) app.database.ruleDao().update(rule) else app.database.ruleDao().insert(rule)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RuleEditActivity, R.string.rule_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
