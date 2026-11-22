package com.xiaodou.notify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.R
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.databinding.ActivityMainBinding
import com.xiaodou.notify.engine.MuteController
import com.xiaodou.notify.listener.NotificationMonitorService
import com.xiaodou.notify.util.PerfTracer
import com.xiaodou.notify.util.ThemeHelper
import com.xiaodou.notify.util.hasPostNotificationPermission
import com.xiaodou.notify.util.isListenerEnabled
import com.xiaodou.notify.util.openListenerSettings
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    /** 统计页指标数字跳转时暂存的初始筛选维度，切到历史 tab 时消费 */
    private var pendingHistoryFilter: Int = HistoryFragment.FILTER_ALL

    /**
     * v1.0.0 性能：四个 tab 的 Fragment 实例缓存。
     * 旧实现每次切页都 replace → 销毁重建 → 重新 inflate 设置页 69KB 布局、
     * 重新订阅历史页全表 Flow；改为 add 一次 + show/hide 复用后切页不再重复这些开销。
     */
    private val fragmentCache = mutableMapOf<Int, Fragment>()

    /**
     * v1.6.5：当前权限弹窗的唯一引用。
     * 旧实现每处 show() 都新建一个 Dialog 且无人持有引用：onCreate 的引导框、
     * onResume 的「被收回」框会叠在一起；授权返回后也没有任何地方 dismiss，
     * 于是用户看到「已经授权了，引导页却还在」。
     */
    private var permissionDialog: AlertDialog? = null
    /** 本次进程内是否曾看到通知使用权已开（含刚开启、服务尚未连接的窗口期） */
    private var listenerSeenEnabled = false
    /** 从系统设置返回后延迟复核，兼容部分 ROM 写入 enabled_notification_listeners 有延迟 */
    private var listenerRecheck: Runnable? = null

    private val prefs get() = (application as XiaodouApp).prefs

    /** 打开系统通知使用权设置并接收返回；多数 ROM 返回 cancel，故只看当前权限状态 */
    private val listenerSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 从系统设置返回：先收掉发起跳转时的引导框，再按最新权限状态复核
        dismissPermissionDialog()
        syncPermissionState(recheckDelayed = true)
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val RECHECK_DELAY_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.themeStyle(prefs.themeColor))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_history
        } else {
            // 旋转重建后按当前选中的 tab 恢复显示（FragmentManager 已自动恢复 add 过的实例）
            restoreCachedFragments()
            val current = binding.bottomNav.selectedItemId
            showTab(current)
        }

        // v1.6.5：唯一的权限同步入口，幂等；onCreate / onResume / 授权结果 / 设置页返回都走这里
        syncPermissionState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission(this)) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS
            )
        }

    }

    /** 供 StatsFragment 调用：切到历史页并按指定维度筛选 */
    fun openHistoryWithFilter(filter: Int) {
        pendingHistoryFilter = filter
        if (binding.bottomNav.selectedItemId == R.id.nav_history) {
            // 已在历史页时 selectedItemId 不变、不会触发 listener，直接通知已缓存实例
            (fragmentCache[R.id.nav_history] as? HistoryFragment)?.applyIncomingFilter(filter)
            pendingHistoryFilter = HistoryFragment.FILTER_ALL
        } else {
            binding.bottomNav.selectedItemId = R.id.nav_history
        }
    }

    /** 首次进入某 tab 时创建并 add；已有实例直接复用。 */
    private fun switchTab(tabId: Int) {
        PerfTracer.section("switchTab-$tabId") {
            if (!fragmentCache.containsKey(tabId)) {
                val fragment = when (tabId) {
                    R.id.nav_history -> HistoryFragment.newInstance(pendingHistoryFilter)
                    R.id.nav_rules -> RulesFragment()
                    R.id.nav_stats -> StatsFragment()
                    R.id.nav_settings -> SettingsFragment()
                    else -> return@section
                }
                pendingHistoryFilter = HistoryFragment.FILTER_ALL
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragment_container, fragment, fragmentTag(tabId))
                    .hide(fragment)
                    .commitNow()
                fragmentCache[tabId] = fragment
            }
            showTab(tabId)
        }
    }

    /** 显示目标 tab、隐藏其余；触发 onHiddenChanged 让各页刷新状态。 */
    private fun showTab(tabId: Int) {
        val target = fragmentCache[tabId] ?: return
        PerfTracer.section("showTab-$tabId") {
            supportFragmentManager.beginTransaction().apply {
                fragmentCache.forEach { (id, fragment) ->
                    if (id == tabId) show(fragment) else if (fragment.isAdded) hide(fragment)
                }
                commitNow()
            }
        }
    }

    /** 旋转重建后从 FragmentManager 按 tag 找回已恢复的实例，重建缓存表。 */
    private fun restoreCachedFragments() {
        listOf(
            R.id.nav_history, R.id.nav_rules, R.id.nav_stats,
            R.id.nav_settings
        ).forEach { id ->
            supportFragmentManager.findFragmentByTag(fragmentTag(id))?.let { fragmentCache[id] = it }
        }
    }

    private fun fragmentTag(tabId: Int) = "tab_$tabId"

    /**
     * 权限状态统一收口：
     * 1) 已授权 → 一定关掉旧弹窗（这是「返回后引导框不消失」的直接修复）；
     * 2) 未授权 → 已有弹窗就保持，不重复新建；
     * 3) 首次引导 / 权限被收回分别用不同文案，不再首次启动就误报「被收回」。
     */
    private fun syncPermissionState(recheckDelayed: Boolean = false) {
        val listenerOn = isListenerEnabled(this)
        if (listenerOn) {
            listenerSeenEnabled = true
            cancelListenerRecheck()
            dismissPermissionDialog()
            NotificationMonitorService.rebindIfNeeded(this)
            return
        }
        if (recheckDelayed) scheduleListenerRecheck()
        if (permissionDialog?.isShowing == true) return

        val everEnabled = listenerSeenEnabled || prefs.listenerConnectedAt > 0L
        when {
            !prefs.guideShown && !everEnabled -> {
                prefs.guideShown = true
                showPermissionGuide(lost = false)
            }
            everEnabled -> showPermissionGuide(lost = true)
            // 已引导过但一直未授权：不再模态骚扰，设置页常驻入口与历史空态继续提示
        }
    }

    private fun showPermissionGuide(lost: Boolean) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (lost) R.string.settings_permission else R.string.guide_title)
            .setMessage(if (lost) R.string.settings_permission_lost else R.string.guide_desc)
            .setPositiveButton(R.string.guide_button) { _, _ -> openListenerSettingsSafely() }
            .setNegativeButton(if (lost) R.string.action_cancel else R.string.guide_later, null)
            .setOnDismissListener { permissionDialog = null }
            .show()
        permissionDialog = dialog
    }

    private fun openListenerSettingsSafely() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        try {
            listenerSettingsLauncher.launch(intent)
        } catch (e: Exception) {
            // 少数机型没有标准设置页，退回原有打开方式
            openListenerSettings(this)
        }
    }

    private fun dismissPermissionDialog() {
        permissionDialog?.dismiss()
        permissionDialog = null
    }

    private fun scheduleListenerRecheck() {
        cancelListenerRecheck()
        val runnable = Runnable {
            listenerRecheck = null
            if (!isFinishing && !isDestroyed) syncPermissionState()
        }
        listenerRecheck = runnable
        binding.root.postDelayed(runnable, RECHECK_DELAY_MS)
    }

    private fun cancelListenerRecheck() {
        listenerRecheck?.let { binding.root.removeCallbacks(it) }
        listenerRecheck = null
    }

    override fun onResume() {
        super.onResume()
        // v1.7.1：回前台先对齐静音状态（到期则恢复，未到期则续挂巡检）
        MuteController.recoverIfNeeded(this, prefs)
        // 返回前台先按当前权限状态同步：已授权则关闭遗留弹窗，未授权也只保留一个
        syncPermissionState()
        // FR-H1 边界：监听中断后的漏记透明提示
        val missed = prefs.pendingMissedSince
        if (missed > 0) {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(missed))
            MaterialAlertDialogBuilder(this)
                .setTitle("可能漏记通知")
                .setMessage(getString(R.string.settings_missed_warning, time))
                .setPositiveButton(R.string.action_ok, null)
                .show()
            prefs.pendingMissedSince = 0L
        }
    }

    override fun onStop() {
        // v1.7.1：切后台也对齐一次；进程在此后被杀也不影响，到期时刻已持久化
        MuteController.recoverIfNeeded(this, prefs)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_POST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && !isListenerEnabled(this) && permissionDialog?.isShowing == true) {
            // 用户在系统「通知」弹窗点了允许：不让首启引导框继续滞留。
            // 尚未开启的「通知使用权」改由设置页入口 + 历史空态提示，不阻塞使用。
            dismissPermissionDialog()
            prefs.guideShown = true
            Toast.makeText(this, R.string.guide_listener_hint, Toast.LENGTH_LONG).show()
        }
        syncPermissionState()
    }

    override fun onDestroy() {
        cancelListenerRecheck()
        dismissPermissionDialog()
        super.onDestroy()
    }
}
