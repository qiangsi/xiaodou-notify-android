package com.xiaodou.notify.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.databinding.ActivityCodeVaultBinding
import com.xiaodou.notify.databinding.ItemCodeCardBinding
import com.xiaodou.notify.util.ProHelper
import com.xiaodou.notify.util.ProSheet
import com.xiaodou.notify.util.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.6.0 验证码保险箱：把散落在通知历史里的验证码 / 取件码集中归档，
 * 大字显示、带有效期倒计时、一键复制 —— 原通知划掉也能找回。
 *
 * 付费策略：不锁门。免费版可看最近 3 个（真实体验价值），
 * 想翻全部历史时才弹解锁，且激活后立即刷新为全量。
 */
class CodeVaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeVaultBinding
    private val codePattern = Regex(
        "(?<![A-Za-z0-9])([A-Z0-9]{4,8}|\\d{4,8})(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )
    private val hints = listOf("验证码", "校验码", "动态码", "code", "取件码", "取件")

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.themeStyle((application as XiaodouApp).prefs.themeColor))
        super.onCreate(savedInstanceState)
        binding = ActivityCodeVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) load()
    }

    private fun load() {
        val app = application as XiaodouApp
        val isPro = ProHelper.isActive(app.prefs)
        lifecycleScope.launch(Dispatchers.IO) {
            // 免费取 3 条；Pro 取全部（上限 500 条足够）
            val all = app.database.notificationDao().codeVault(if (isPro) 500 else 3)
            val total = app.database.notificationDao().codeVaultCount()
            withContext(Dispatchers.Main) { render(all, total, isPro) }
        }
    }

    private fun render(list: List<NotificationEntity>, total: Int, isPro: Boolean) {
        val container = binding.listContainer
        container.removeAllViews()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()

        if (list.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.code_vault_empty)
                setTextColor(getColor(R.color.text_tertiary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(8, 48, 8, 8)
            }
            container.addView(empty)
            return
        }

        list.forEach { e ->
            val title = CryptoHelper.decrypt(e.titleEnc)
            val text = CryptoHelper.decrypt(e.textEnc)
            val combined = "$title $text"
            val code = if (hints.any { combined.contains(it, ignoreCase = true) }) {
                codePattern.findAll(combined).map { it.value }.firstOrNull { it.length in 4..8 }
            } else null

            val card = ItemCodeCardBinding.inflate(LayoutInflater.from(this), container, false)
            card.tvCode.text = code ?: "—"
            // 有效期：验证码一般 10 分钟内有效，按到达时间推算
            val ageMin = ((now - e.timestamp) / 60_000L).coerceAtLeast(0)
            val leftMin = 10 - ageMin
            card.tvMeta.text = if (leftMin > 0)
                "${fmt.format(Date(e.timestamp))}　·　${getString(R.string.code_vault_min_left, leftMin.toInt())}"
            else
                "${fmt.format(Date(e.timestamp))}　·　${getString(R.string.code_vault_expired)}"
            card.tvMeta.setTextColor(
                getColor(if (leftMin > 0) R.color.text_secondary else R.color.text_tertiary)
            )
            card.tvFrom.text = e.appName
            card.btnCopy.setOnClickListener {
                val value = code ?: return@setOnClickListener
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("验证码", value))
                Toast.makeText(this, R.string.code_vault_copied, Toast.LENGTH_SHORT).show()
            }
            container.addView(card.root)
        }

        // 免费版底部「解锁查看全部」钩子：还有多少历史码 + 点击即解锁并立即刷新
        if (!isPro) {
            val hintView = TextView(this).apply {
                val more = (total - list.size).coerceAtLeast(0)
                text = if (more > 0)
                    getString(R.string.code_vault_locked_hint, more)
                else getString(R.string.code_vault_free_limit)
                setTextColor(getColor(R.color.primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(8, 28, 8, 8)
                setOnClickListener {
                    ProSheet.gate(
                        this@CodeVaultActivity,
                        (application as XiaodouApp).prefs,
                        getString(R.string.pro_feature_code_vault)
                    ) { load() } // 激活成功立即刷新为全量
                }
            }
            container.addView(hintView)
        }
    }
}
