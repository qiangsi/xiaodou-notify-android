package com.xiaodou.notify.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.databinding.FragmentSettingsBinding
import com.xiaodou.notify.engine.ActionExecutor
import com.xiaodou.notify.listener.XiaodouWidgetProvider
import com.xiaodou.notify.listener.NotificationMonitorService
import com.xiaodou.notify.util.AppLockGuard
import com.xiaodou.notify.util.AppLockHelper
import com.xiaodou.notify.util.IconHelper
import com.xiaodou.notify.util.AppLockPolicy
import com.xiaodou.notify.util.LockRecovery
import com.xiaodou.notify.util.PinHelper
import com.xiaodou.notify.util.ProHelper
import com.xiaodou.notify.util.ProSheet
import com.xiaodou.notify.util.ThemeHelper
import com.xiaodou.notify.util.isListenerEnabled
import com.xiaodou.notify.util.openListenerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: XiaodouApp
    private var lockSwitchBusy = false

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? -> uri?.let { writeCsv(it) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = requireActivity().application as XiaodouApp
        val prefs = app.prefs

        // —— v1.5.0 Pro 买断：状态展示 + 激活入口 ——
        fun refreshProStatus() {
            binding.tvProStatus.text = ProHelper.statusText(prefs)
        }
        refreshProStatus()
        binding.cardPro.setOnClickListener {
            // v1.6.0：统一走 ProSheet，激活成功后刷新状态文案
            ProSheet.show(requireContext(), prefs) { refreshProStatus() }
        }

        // —— v1.6.0 重要通知守护（Pro）——
        fun refreshVipStatus() {
            binding.tvVipGuardStatus.text = if (prefs.vipGuardEnabled && prefs.vipWords().isNotEmpty())
                getString(R.string.vip_guard_status_on, prefs.vipWords().size)
            else getString(R.string.vip_guard_status_off)
        }
        refreshVipStatus()
        binding.rowVipGuard.setOnClickListener {
            ProSheet.gate(requireContext(), prefs, getString(R.string.pro_feature_vip_guard)) {
                showVipGuardDialog(prefs, ::refreshVipStatus)
            }
        }

        // —— v1.6.0 验证码保险箱：不锁门，免费可看最近 3 个，翻全部历史时才解锁（先体验后付费）——
        binding.rowCodeVault.setOnClickListener {
            startActivity(Intent(requireContext(), CodeVaultActivity::class.java))
        }

        fun refreshPermission() {
            binding.tvPermission.text = if (isListenerEnabled(requireContext()))
                getString(R.string.settings_permission_status_on)
            else getString(R.string.settings_permission_status_off)
        }
        refreshPermission()
        binding.cardPermission.setOnClickListener { openListenerSettings(requireContext()) }

        // 运行状态自检（v1.3.2）：权限/监听/过滤三态一眼可见
        fun refreshStatus() {
            val permissionOn = isListenerEnabled(requireContext())
            binding.tvStatusPermission.text = getString(
                if (permissionOn) R.string.settings_status_on else R.string.settings_status_off
            )
            val connected = permissionOn && NotificationMonitorService.isConnected
            binding.tvStatusListener.text = getString(
                if (connected) R.string.settings_status_on else R.string.settings_status_off
            )
            binding.tvStatusFilter.text = getString(
                if (prefs.filterPaused) R.string.settings_status_filter_off
                else R.string.settings_status_filter_on
            )
        }
        refreshStatus()
        binding.btnRebind.setOnClickListener {
            NotificationMonitorService.rebindIfNeeded(requireContext())
            Toast.makeText(requireContext(), R.string.settings_status_rebind_done, Toast.LENGTH_SHORT).show()
            binding.root.postDelayed({ if (_binding != null) refreshStatus() }, 800)
        }
        binding.btnDiagnostic.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnosticActivity::class.java))
        }

        // 外观：深浅模式 + 配色（FR-E6），切换即时生效并记忆
        fun refreshThemeLabels() {
            binding.tvThemeModeValue.text = getString(R.string.theme_mode_label, ThemeHelper.MODE_NAMES[prefs.themeMode.coerceIn(0..2)])
            binding.tvThemeColorValue.text = getString(R.string.theme_color_label, ThemeHelper.COLOR_NAMES[prefs.themeColor.coerceIn(0 until ThemeHelper.COLOR_COUNT)])
            binding.tvLauncherIconValue.text = getString(R.string.launcher_icon_label, IconHelper.ICON_NAMES[prefs.launcherIcon.coerceIn(0 until IconHelper.ICON_COUNT)])
        }
        refreshThemeLabels()
        binding.tvThemeMode.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_appearance)
                .setSingleChoiceItems(ThemeHelper.MODE_NAMES, prefs.themeMode.coerceIn(0..2)) { d, which ->
                    prefs.themeMode = which
                    ThemeHelper.applyNightMode(which)
                    XiaodouWidgetProvider.refreshAll(requireContext())
                    d.dismiss()
                    requireActivity().recreate()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        binding.tvThemeColor.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.theme_color_label, ThemeHelper.COLOR_NAMES[prefs.themeColor.coerceIn(0 until ThemeHelper.COLOR_COUNT)]))
                .setSingleChoiceItems(ThemeHelper.COLOR_NAMES, prefs.themeColor.coerceIn(0 until ThemeHelper.COLOR_COUNT)) { d, which ->
                    prefs.themeColor = which
                    XiaodouWidgetProvider.refreshAll(requireContext())
                    d.dismiss()
                    requireActivity().recreate()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        binding.tvLauncherIcon.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.launcher_icon_dialog_title)
                .setSingleChoiceItems(IconHelper.ICON_NAMES, prefs.launcherIcon.coerceIn(0 until IconHelper.ICON_COUNT)) { d, which ->
                    val ok = IconHelper.switchIcon(requireContext(), which)
                    if (ok) {
                        prefs.launcherIcon = which
                        refreshThemeLabels()
                        android.widget.Toast.makeText(
                            requireContext(), R.string.launcher_icon_hint, android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            requireContext(), "图标切换失败，请重试", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    d.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        // 暂停过滤（FR-E4）：仅记录不过滤
        binding.switchFilterPaused.isChecked = prefs.filterPaused
        binding.switchFilterPaused.setOnCheckedChangeListener { _, c ->
            prefs.filterPaused = c
            XiaodouWidgetProvider.refreshAll(requireContext())
            refreshStatus()
        }

        // AI 开关与阈值
        binding.switchAi.isChecked = prefs.aiEnabled
        binding.switchAi.setOnCheckedChangeListener { _, checked -> prefs.aiEnabled = checked }
        binding.seekThreshold.progress = prefs.aiThreshold
        binding.tvThreshold.text = getString(R.string.settings_ai_threshold_value, prefs.aiThreshold)
        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                binding.tvThreshold.text = getString(R.string.settings_ai_threshold_value, p)
                if (fromUser) prefs.aiThreshold = p
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 通知增强
        binding.switchUndo.isChecked = prefs.undoEnabled
        binding.switchUndo.setOnCheckedChangeListener { _, c -> prefs.undoEnabled = c }
        binding.switchSmartmute.isChecked = prefs.smartMuteEnabled
        binding.switchSmartmute.setOnCheckedChangeListener { _, c -> prefs.smartMuteEnabled = c }
        binding.switchCodecopy.isChecked = prefs.codeCopyEnabled
        binding.switchCodecopy.setOnCheckedChangeListener { _, c -> prefs.codeCopyEnabled = c }

        // 智能静音可配（FR-E2）
        fun refreshMuteCfg() {
            binding.tvMuteCfg.text = getString(R.string.settings_mute_threshold, prefs.muteThreshold, prefs.muteWindowSec)
            binding.tvMuteDuration.text = getString(R.string.settings_mute_duration, prefs.muteDurationSec)
        }
        refreshMuteCfg()
        binding.tvMuteCfg.setOnClickListener { showNumberPicker("触发条数", prefs.muteThreshold, 2..100) { prefs.muteThreshold = it; refreshMuteCfg() } }
        binding.tvMuteDuration.setOnClickListener { showNumberPicker("静音时长(秒)", prefs.muteDurationSec, 5..600) { prefs.muteDurationSec = it; refreshMuteCfg() } }
        binding.btnUnmute.setOnClickListener {
            ActionExecutor.unmute(requireContext())
            Toast.makeText(requireContext(), R.string.settings_unmuted, Toast.LENGTH_SHORT).show()
        }

        binding.btnExport.setOnClickListener {
            // FR-H6 + v1.5.0 Pro：免费版限近 7 / 30 天，"全部"需 Pro
            val isPro = ProHelper.isActive(prefs)
            val ranges = if (isPro)
                arrayOf("全部", "近 7 天", "近 30 天")
            else
                arrayOf("近 7 天", "近 30 天", "全部（需 Pro）")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("导出范围")
                .setItems(ranges) { _, which ->
                    val selection = if (isPro) which else which + 1
                    if (which == 2 && !isPro) {
                        // v1.6.0：激活成功后立即兑现「导出全部」，不再让用户自己再点一次
                        ProSheet.gate(
                            requireContext(), prefs,
                            getString(R.string.pro_feature_export_all)
                        ) {
                            exportDays = 0
                            exportLauncher.launch("小豆通知_${System.currentTimeMillis()}.csv")
                        }
                        return@setItems
                    }
                    exportDays = when (selection) { 1 -> 7; 2 -> 30; else -> 0 }
                    exportLauncher.launch("小豆通知_${System.currentTimeMillis()}.csv")
                }
                .show()
        }

        // 排除 App（FR-E8）
        binding.btnExcludeApps.setOnClickListener { showExcludeApps() }
        binding.switchExcludeMode.isChecked = prefs.excludeNoRecord
        binding.switchExcludeMode.setOnCheckedChangeListener { _, c -> prefs.excludeNoRecord = c }

        // 重置模型（FR-A4 / P3）
        binding.btnResetModel.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.settings_reset_model_confirm)
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        app.database.aiFeedbackDao().clearAll()
                        app.database.notificationDao().resetAllScores()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.settings_reset_model_done, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        // 应用锁（FR-E5）：开启需先自设密码，关闭立即生效，退后台/锁屏后重新验证
        binding.switchAppLock.isChecked = prefs.appLockEnabled
        binding.switchAppLock.setOnCheckedChangeListener { _, enable ->
            if (lockSwitchBusy) return@setOnCheckedChangeListener
            if (enable) {
                if (PinHelper.hasPin(prefs)) {
                    // 已有密码：验证旧密码后重新启用
                    AppLockHelper.prompt(requireActivity(), prefs,
                        onSuccess = { enableAppLock(prefs) },
                        onFail = {
                            setLockSwitch(false)
                            Toast.makeText(requireContext(), R.string.app_lock_canceled, Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    // 首次开启：先设置一个 4-6 位数字密码
                    showPinSetup(
                        onDone = {
                            PinHelper.setPin(prefs, it)
                            enableAppLock(prefs)
                            Toast.makeText(requireContext(), R.string.app_lock_pin_set_done, Toast.LENGTH_SHORT).show()
                        },
                        onCancel = { setLockSwitch(false) }
                    )
                }
            } else {
                prefs.appLockEnabled = false
                AppLockPolicy.clear(prefs)
                AppLockGuard.unlock()
                refreshLockConfig()
                Toast.makeText(requireContext(), R.string.app_lock_off, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLockNow.setOnClickListener {
            if (!prefs.appLockEnabled) {
                Toast.makeText(requireContext(), R.string.app_lock_need_enable, Toast.LENGTH_SHORT).show()
            } else {
                AppLockGuard.lockNow()
                Toast.makeText(requireContext(), R.string.app_lock_locked, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnChangePin.setOnClickListener {
            if (!PinHelper.hasPin(prefs)) {
                Toast.makeText(requireContext(), R.string.app_lock_need_enable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 修改密码前先验证当前密码
            AppLockHelper.prompt(requireActivity(), prefs,
                onSuccess = {
                    showPinSetup(
                        onDone = {
                            PinHelper.setPin(prefs, it)
                            Toast.makeText(requireContext(), R.string.app_lock_pin_set_done, Toast.LENGTH_SHORT).show()
                        },
                        onCancel = {}
                    )
                },
                onFail = {}
            )
        }

        // v1.4.0：应用锁的离开时长 / 解锁方式 / 备用恢复
        bindLockConfig()

        // v1.4.0：设置页底部小字入口
        binding.entryUpdate.setOnClickListener { openUpdatePage() }
        binding.entryAbout.setOnClickListener { showAboutDialog() }
        binding.entryFeedback.setOnClickListener { openFeedbackPage() }

        binding.btnBatteryOpt.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = requireContext().getSystemService(PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                    Toast.makeText(requireContext(), R.string.battery_whitelisted, Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        startActivity(Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${requireContext().packageName}")
                        ))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), R.string.battery_whitelist_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.settings_clear_confirm)
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        app.database.notificationDao().clearAll()
                        app.database.aiFeedbackDao().clearAll()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    /** 更新版本：优先打开 GitHub Release 页面，失败则提示手动查看 */
    private fun openUpdatePage() {
        val url = "https://github.com/qiangsi/xiaodou-notify-android/releases"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.entry_update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 关于：版本、隐私说明与项目地址 */
    private fun showAboutDialog() {
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            "1.4.0"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.entry_about)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    /** 意见反馈：打开 GitHub Issues 页面 */
    private fun openFeedbackPage() {
        val url = "https://github.com/qiangsi/xiaodou-notify-android/issues/new"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.entry_feedback_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private var exportDays = 0

    private fun showNumberPicker(title: String, current: Int, range: IntRange, onPick: (Int) -> Unit) {
        val values = (range.first..range.last).map { it.toString() }.toTypedArray()
        val start = current - range.first
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(values, start.coerceIn(0, values.size - 1)) { dialog, which ->
                onPick(range.first + which)
                dialog.dismiss()
            }
            .show()
    }

    private fun showExcludeApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = requireContext().packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { pm.getApplicationLabel(it).toString() to it.packageName }
                .sortedBy { it.first }
                .take(100) // 避免对话框过长
            withContext(Dispatchers.Main) {
                if (installed.isEmpty()) return@withContext
                val names = installed.map { it.first }.toTypedArray()
                val current = app.prefs.excludedApps.split(",").toSet()
                val checked = BooleanArray(installed.size) { i -> installed[i].second in current }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_exclude_apps)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(R.string.action_save) { _, _ ->
                        val picked = installed.filterIndexed { i, _ -> checked[i] }.map { it.second }
                        app.prefs.excludedApps = picked.joinToString(",")
                        Toast.makeText(requireContext(), R.string.rule_saved, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun writeCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = app.database.notificationDao()
                val list = if (exportDays > 0) {
                    val from = System.currentTimeMillis() - exportDays * 86_400_000L
                    dao.queryForExport(from, System.currentTimeMillis(), "")
                } else {
                    dao.queryForExport(0L, Long.MAX_VALUE, "")
                }
                val out = requireContext().contentResolver.openOutputStream(uri) ?: return@launch
                OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                    w.write("\uFEFF") // UTF-8 BOM，Excel 中文不乱码
                    w.write("时间,应用,包名,标题,正文,处理结果,原因,骚扰概率,类型\n")
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    list.forEach { e ->
                        fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
                        w.write(listOf(
                            sdf.format(Date(e.timestamp)),
                            esc(e.appName), esc(e.packageName),
                            esc(CryptoHelper.decrypt(e.titleEnc)),
                            esc(CryptoHelper.decrypt(e.textEnc)),
                            e.result, esc(e.filterReason ?: ""), e.score.toString(), esc(e.category ?: "")
                        ).joinToString(","))
                        w.write("\n")
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.export_done), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.tvPermission.text = if (isListenerEnabled(requireContext()))
                getString(R.string.settings_permission_status_on)
            else getString(R.string.settings_permission_status_off)
            val permissionOn = isListenerEnabled(requireContext())
            binding.tvStatusPermission.text = getString(
                if (permissionOn) R.string.settings_status_on else R.string.settings_status_off
            )
            val connected = permissionOn && NotificationMonitorService.isConnected
            binding.tvStatusListener.text = getString(
                if (connected) R.string.settings_status_on else R.string.settings_status_off
            )
            binding.tvStatusFilter.text = getString(
                if (app.prefs.filterPaused) R.string.settings_status_filter_off
                else R.string.settings_status_filter_on
            )
        }
    }

    private fun setLockSwitch(checked: Boolean) {
        lockSwitchBusy = true
        binding.switchAppLock.isChecked = checked
        lockSwitchBusy = false
    }

    private fun enableAppLock(prefs: com.xiaodou.notify.util.Prefs) {
        prefs.appLockEnabled = true
        // 清除残留的离开记录，避免刚开启就因旧时间戳立即验证
        AppLockPolicy.clear(prefs)
        AppLockGuard.unlock()
        setLockSwitch(true)
        refreshLockConfig()
        Toast.makeText(requireContext(), R.string.app_lock_on, Toast.LENGTH_SHORT).show()
    }

    /** 刷新应用锁三项配置（离开时长 / 解锁方式 / 备用恢复）的显示与可用性 */
    private fun refreshLockConfig() {
        if (_binding == null) return
        val enabled = app.prefs.appLockEnabled
        binding.rowLockAway.isEnabled = enabled
        binding.rowLockMethod.isEnabled = enabled
        binding.rowLockRecovery.isEnabled = enabled
        binding.tvLockAwayValue.text = awayLabel(app.prefs.appLockAwayMs)
        binding.tvLockMethodValue.text = methodLabel(app.prefs.appLockMethod)
        binding.tvLockRecoveryValue.text = getString(
            if (LockRecovery.hasRecovery(app.prefs)) R.string.lock_recovery_set
            else R.string.lock_recovery_unset
        )
    }

    private fun awayLabel(ms: Long): String = getString(
        when (ms) {
            15_000L -> R.string.lock_away_15s
            60_000L -> R.string.lock_away_1min
            180_000L -> R.string.lock_away_3min
            else -> R.string.lock_away_always
        }
    )

    private fun methodLabel(method: Int): String = getString(
        when (method) {
            AppLockHelper.METHOD_PIN -> R.string.lock_method_pin
            AppLockHelper.METHOD_BIOMETRIC -> R.string.lock_method_biometric
            else -> R.string.lock_method_both
        }
    )

    /** 应用锁三项配置的选择器：离开时长、解锁方式、备用恢复 */
    private fun bindLockConfig() {
        refreshLockConfig()

        binding.rowLockAway.setOnClickListener {
            if (!app.prefs.appLockEnabled) return@setOnClickListener
            val values = longArrayOf(0L, 15_000L, 60_000L, 180_000L)
            val labels = arrayOf(
                getString(R.string.lock_away_always), getString(R.string.lock_away_15s),
                getString(R.string.lock_away_1min), getString(R.string.lock_away_3min)
            )
            val cur = values.indexOf(app.prefs.appLockAwayMs).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lock_away_title)
                .setSingleChoiceItems(labels, cur) { d, which ->
                    app.prefs.appLockAwayMs = values[which]
                    refreshLockConfig()
                    d.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        binding.rowLockMethod.setOnClickListener {
            if (!app.prefs.appLockEnabled) return@setOnClickListener
            val labels = arrayOf(
                getString(R.string.lock_method_pin),
                getString(R.string.lock_method_biometric),
                getString(R.string.lock_method_both)
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lock_method_title)
                .setSingleChoiceItems(labels, app.prefs.appLockMethod.coerceIn(0..2)) { d, which ->
                    if (which == AppLockHelper.METHOD_BIOMETRIC && !AppLockHelper.isBiometricCapable(requireContext())) {
                        Toast.makeText(requireContext(), R.string.app_lock_no_device, Toast.LENGTH_SHORT).show()
                    } else {
                        app.prefs.appLockMethod = which
                        refreshLockConfig()
                    }
                    d.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        binding.rowLockRecovery.setOnClickListener {
            if (!app.prefs.appLockEnabled) return@setOnClickListener
            showRecoverySetup()
        }
    }

    private fun showRecoverySetup() {
        val qIdx = app.prefs.recoveryQuestionIndex.coerceIn(0..LockRecovery.QUESTIONS.lastIndex)
        val questions = LockRecovery.QUESTIONS.map { getString(it) }.toTypedArray()
        val answer = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.recovery_answer_hint)
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        var picked = qIdx
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lock_recovery_title)
            .setSingleChoiceItems(questions, qIdx) { _, which -> picked = which }
            .setView(answer)
            .setPositiveButton(R.string.action_save, null)
            .setNeutralButton(R.string.lock_recovery_clear, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val a = answer.text.toString()
                        if (a.trim().length < 2) {
                            answer.error = getString(R.string.recovery_answer_empty)
                        } else {
                            LockRecovery.setRecovery(app.prefs, picked, a)
                            dismiss()
                            refreshLockConfig()
                            Toast.makeText(requireContext(), R.string.lock_recovery_saved, Toast.LENGTH_SHORT).show()
                        }
                    }
                    getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        LockRecovery.clear(app.prefs)
                        dismiss()
                        refreshLockConfig()
                        Toast.makeText(requireContext(), R.string.lock_recovery_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
                show()
            }
    }

    /** 弹对话框设置 PIN：两次输入一致且为 4-6 位数字才回调 onDone */
    private fun showPinSetup(onDone: (String) -> Unit, onCancel: () -> Unit) {
        val ctx = requireContext()
        fun newField(hintRes: Int) = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = getString(hintRes)
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val first = newField(R.string.app_lock_pin_set_hint)
        val second = newField(R.string.app_lock_pin_confirm_hint)
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(first)
            addView(second)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.app_lock_pin_set_title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    !PinHelper.isValidFormat(first.text.toString()) -> first.error = getString(R.string.app_lock_pin_invalid)
                    first.text.toString() != second.text.toString() -> second.error = getString(R.string.app_lock_pin_mismatch)
                    else -> {
                        dialog.dismiss()
                        onDone(first.text.toString())
                    }
                }
            }
        }
        dialog.setOnCancelListener { onCancel() }
        dialog.show()
    }

    /** v1.6.0 重要通知守护配置：守护词 + 启用开关。仅 Pro 可进入（由 ProSheet.gate 保证）。 */
    private fun showVipGuardDialog(prefs: com.xiaodou.notify.util.Prefs, onSaved: () -> Unit) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(ctx).apply {
            hint = getString(R.string.vip_guard_hint)
            setText(prefs.vipGuardWords)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            maxLines = 3
        }
        box.addView(input, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val sw = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = getString(R.string.vip_guard_enable)
            isChecked = prefs.vipGuardEnabled
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(sw)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vip_guard_dialog_title)
            .setMessage(R.string.vip_guard_dialog_msg)
            .setView(box)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                prefs.vipGuardWords = input.text.toString().trim()
                prefs.vipGuardEnabled = sw.isChecked
                onSaved()
                Toast.makeText(ctx, R.string.vip_guard_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
