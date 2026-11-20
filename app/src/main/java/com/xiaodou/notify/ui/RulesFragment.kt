package com.xiaodou.notify.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.Actions
import com.xiaodou.notify.data.FilterRule
import com.xiaodou.notify.databinding.FragmentRulesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RuleAdapter
    private lateinit var app: XiaodouApp
    private var currentRules: List<FilterRule> = emptyList()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { importRules(it) } }
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { exportRules(it) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = requireActivity().application as XiaodouApp
        adapter = RuleAdapter(
            onEdit = { rule ->
                startActivity(Intent(requireContext(), RuleEditActivity::class.java).putExtra("id", rule.id))
            },
            onToggle = { rule, checked ->
                lifecycleScope.launch(Dispatchers.IO) {
                    app.database.ruleDao().update(rule.copy(enabled = checked))
                }
            },
            onDelete = { rule -> confirmDelete(rule) },
            onMove = { rule, delta -> moveRule(rule, delta) },
            onDuplicate = { rule -> duplicate(rule) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), RuleEditActivity::class.java))
        }

        // 预设模板入口
        binding.btnTemplates.setOnClickListener { showTemplates() }
        binding.btnExportRules.setOnClickListener { exportLauncher.launch("小豆通知_规则_${System.currentTimeMillis()}.json") }
        binding.btnImportRules.setOnClickListener { importLauncher.launch("application/json") }

        viewLifecycleOwner.lifecycleScope.launch {
            app.database.ruleDao().observeAll().collectLatest { list ->
                currentRules = list
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun confirmDelete(rule: FilterRule) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.rule_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { app.database.ruleDao().delete(rule) }
                Toast.makeText(requireContext(), R.string.rule_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 上/下移：交换相邻两条的 priority，触发 Flow 重排 */
    private fun moveRule(rule: FilterRule, delta: Int) {
        val idx = currentRules.indexOfFirst { it.id == rule.id }
        val target = idx + delta
        if (idx < 0 || target < 0 || target >= currentRules.size) return
        val other = currentRules[target]
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = app.database.ruleDao()
            val p1 = rule.priority
            val p2 = other.priority
            dao.update(rule.copy(priority = p2))
            dao.update(other.copy(priority = p1))
        }
    }

    private fun duplicate(rule: FilterRule) {
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.ruleDao().insert(
                rule.copy(id = 0, name = "${rule.name}（副本）", priority = rule.priority - 1, createdAt = System.currentTimeMillis())
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), R.string.rule_duplicated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** FR-E7：规则导出为 JSON */
    private fun exportRules(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                currentRules.forEach { r ->
                    arr.put(JSONObject().apply {
                        put("name", r.name); put("enabled", r.enabled)
                        put("packageNames", r.packageNames); put("keywordInclude", r.keywordInclude)
                        put("keywordExclude", r.keywordExclude); put("timeStartMin", r.timeStartMin)
                        put("timeEndMin", r.timeEndMin); put("daysOfWeek", r.daysOfWeek)
                        put("chargingOnly", r.chargingOnly); put("screenOffOnly", r.screenOffOnly)
                        put("action", r.action); put("actionParam", r.actionParam); put("priority", r.priority)
                    })
                }
                val out = requireContext().contentResolver.openOutputStream(uri) ?: return@launch
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(arr.toString()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.rule_export_done, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.rule_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** FR-E7：导入 JSON 规则，重名跳过（冲突校验） */
    private fun importRules(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@launch
                val arr = JSONArray(text)
                val existingNames = currentRules.map { it.name }.toSet()
                var added = 0; var skipped = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name", "").ifBlank { "导入规则 $i" }
                    if (name in existingNames) { skipped++; continue }
                    val rule = FilterRule(
                        name = name,
                        enabled = o.optBoolean("enabled", true),
                        packageNames = o.optString("packageNames", ""),
                        keywordInclude = o.optString("keywordInclude", ""),
                        keywordExclude = o.optString("keywordExclude", ""),
                        timeStartMin = o.optInt("timeStartMin", -1),
                        timeEndMin = o.optInt("timeEndMin", -1),
                        daysOfWeek = o.optString("daysOfWeek", ""),
                        chargingOnly = o.optBoolean("chargingOnly", false),
                        screenOffOnly = o.optBoolean("screenOffOnly", false),
                        action = o.optString("action", Actions.DISMISS).takeIf { it in Actions.ALL } ?: Actions.DISMISS,
                        actionParam = o.optString("actionParam", ""),
                        priority = o.optInt("priority", 0)
                    )
                    app.database.ruleDao().insert(rule)
                    added++
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.rule_import_done, added, skipped), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.rule_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showTemplates() {
        val names = arrayOf(
            getString(R.string.template_promo),
            getString(R.string.template_work_mute),
            getString(R.string.template_family_call),
            getString(R.string.template_express_tts)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.template_pick_title)
            .setItems(names) { _, which ->
                val rule = when (which) {
                    0 -> FilterRule(name = names[0], keywordInclude = "促销,优惠,秒杀,红包,限时,免费领", action = Actions.DISMISS, priority = 100)
                    1 -> FilterRule(name = names[1], keywordInclude = "工作,加班,会议,群", timeStartMin = 18 * 60, timeEndMin = 9 * 60, action = Actions.MUTE, priority = 90)
                    2 -> FilterRule(name = names[2], keywordInclude = "爸,妈,家人,老婆,孩子", action = Actions.CALL_UI, priority = 80)
                    else -> FilterRule(name = names[3], keywordInclude = "快递,已发货,取件,签收", action = Actions.TTS, priority = 70)
                }
                lifecycleScope.launch(Dispatchers.IO) { app.database.ruleDao().insert(rule) }
                Toast.makeText(requireContext(), R.string.template_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
