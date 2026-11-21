package com.xiaodou.notify.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.R
import com.xiaodou.notify.data.AiFeedback
import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.databinding.ActivityNotificationDetailBinding
import com.xiaodou.notify.listener.NotificationBridge
import com.xiaodou.notify.util.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationDetailBinding
    private var entity: NotificationEntity? = null
    private lateinit var app: XiaodouApp

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.themeStyle((application as XiaodouApp).prefs.themeColor))
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as XiaodouApp

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.detail_title)

        val id = intent.getLongExtra("id", -1L)
        lifecycleScope.launch {
            entity = withContext(Dispatchers.IO) { app.database.notificationDao().getById(id) }
            entity?.let { render(it) } ?: finish()
        }

        binding.btnSpam.setOnClickListener { mark(1) }
        binding.btnNormal.setOnClickListener { mark(0) }
        binding.btnRestore.setOnClickListener { restore() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnTodo.setOnClickListener { toggleTodo() }
    }

    private fun toggleTodo() {
        val e = entity ?: return
        if (app.prefs.isTodo(e.id)) {
            app.prefs.removeTodo(e.id)
            Toast.makeText(this, R.string.todo_done_toast, Toast.LENGTH_SHORT).show()
        } else {
            app.prefs.addTodo(e.id)
            Toast.makeText(this, R.string.todo_add_toast, Toast.LENGTH_SHORT).show()
        }
        renderTodoState(e)
    }

    private fun renderTodoState(e: NotificationEntity) {
        binding.btnTodo.text = getString(if (app.prefs.isTodo(e.id)) R.string.todo_done else R.string.todo_add)
    }

    private fun render(e: NotificationEntity) {
        binding.tvApp.text = e.appName
        binding.tvTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(e.timestamp))
        binding.tvTitle.text = CryptoHelper.decrypt(e.titleEnc)
        binding.tvText.text = CryptoHelper.decrypt(e.textEnc)
        binding.tvResult.text = when {
            e.result == Result.AI -> "AI 已过滤（${e.filterReason ?: ""}）"
            e.result == Result.RULE -> "规则已处理（${e.filterReason ?: ""}）"
            else -> "已保留" + (e.filterReason?.let { "（$it）" } ?: "")
        }
        binding.progressScore.progress = e.score
        binding.tvScore.text = "骚扰概率 ${e.score}%　（0% 正常 — 100% 骚扰）"
        // FR-H5：该通知已离开通知栏，历史保留的是到达时的内容快照。
        // 如实说明边界，不虚构「已被发送方撤回」结论。
        binding.tvRecalled.visibility = if (e.removedReason != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvRecalled.text = "此通知已离开通知栏，这里保留的是它到达通知栏时的内容快照。若发送方随后撤回或删除，你仍可在此查看；未产生过通知的消息无法捕获。"
        renderTodoState(e)
    }

    private fun mark(label: Int) {
        val e = entity ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            app.database.notificationDao().setMarked(e.id, label)
            app.database.aiFeedbackDao().insert(
                AiFeedback(packageName = e.packageName, textEnc = e.textEnc, label = label)
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NotificationDetailActivity, R.string.marked_toast, Toast.LENGTH_SHORT).show()
                entity = app.database.notificationDao().getById(e.id)
                entity?.let { render(it) }
            }
        }
    }

    private fun restore() {
        val e = entity ?: return
        NotificationBridge.showRestored(this, e.id, CryptoHelper.decrypt(e.titleEnc), CryptoHelper.decrypt(e.textEnc), e.appName)
        Toast.makeText(this, R.string.restored_toast, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage("删除这条记录？")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val e = entity ?: return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) { app.database.notificationDao().delete(e) }
                Toast.makeText(this, R.string.deleted_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
