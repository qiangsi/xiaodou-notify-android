package com.xiaodou.notify.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.R

/**
 * v1.6.0 统一 Pro 解锁面板。
 *
 * 修复：过去各门控点只弹一个 Toast（文案却写「点此解锁」），根本点不动，
 * 用户被拦下后没有任何解锁入口。现在所有付费点统一走 [gate]：
 *   - 已激活：直接执行原操作，不打扰；
 *   - 未激活：弹出真正可输入激活码的面板，激活成功后立即兑现刚才被拦下的那个操作。
 * 这个「付费瞬间立即兑现」的闭环，是转化率的关键。
 */
object ProSheet {

    /** 门控：已激活立即执行；未激活弹出解锁面板，激活成功后立即兑现。 */
    fun gate(context: Context, prefs: Prefs, featureName: String, onActive: () -> Unit) {
        if (ProHelper.isActive(prefs)) {
            onActive()
            return
        }
        show(context, prefs, featureName, onActive)
    }

    /** 展示解锁面板。已激活时改为展示状态（并保留「更换激活码」入口），不重复激活。 */
    fun show(
        context: Context, prefs: Prefs, featureName: String = "",
        onSuccess: (() -> Unit)? = null, forceInput: Boolean = false
    ) {
        if (ProHelper.isActive(prefs) && !forceInput) {
            showStatus(context, prefs, onSuccess)
            return
        }
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = ThemeHelper.resolveColor(context, androidx.appcompat.R.attr.colorPrimary, R.color.primary)
        val textMain = ThemeHelper.resolveColor(context, android.R.attr.textColorPrimary, R.color.text_primary)
        val textSub = ThemeHelper.resolveColor(context, android.R.attr.textColorSecondary, R.color.text_secondary)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(2))
        }
        if (featureName.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.pro_sheet_hook, featureName)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(primary)
                setTypeface(typeface, Typeface.BOLD)
            })
            root.addView(TextView(context).apply {
                text = context.getString(R.string.pro_sheet_hook_sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(textSub)
                setPadding(0, dp(2), 0, dp(6))
            })
        }
        // v1.7.8 关键修复：输入框必须放在面板最顶部。
        // 旧版把输入框排在权益说明之后，内容一多就被挤出可见区域，
        // 用户看不到输入框、只看到底部固定的「立即激活」，点下去校验空串 →
        // 「激活码无效」，于是反馈「没有输入入口」。输入框置顶即可一眼看到。
        if (prefs.proActivated && !ProHelper.isActive(prefs)) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.pro_sheet_expired_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(primary)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(4))
            })
        }
        root.addView(TextView(context).apply {
            text = context.getString(R.string.pro_sheet_input_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(textMain)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        val input = EditText(context).apply {
            hint = context.getString(R.string.pro_dialog_hint)
            // v1.6.1：激活码内含区分大小写的 Base64URL，不能再强制键盘大写
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText("")
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        root.addView(TextView(context).apply {
            text = context.getString(R.string.pro_sheet_no_code)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { showBuy(context) }
        })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.pro_sheet_benefits)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSub)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(2))
        })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.pro_sheet_price)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(primary)
            setPadding(0, dp(6), 0, dp(2))
        })

        // ScrollView 包裹：小屏或大字体下，全部内容都能滚动看到，不再裁掉底部
        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pro_sheet_title)
            .setView(scroll)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.pro_sheet_activate, null)
            .create()
        // 覆盖默认按钮监听，校验失败时不关闭面板，用户可重新输入
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    input.error = context.getString(R.string.pro_activate_empty)
                    input.requestFocus()
                    return@setOnClickListener
                }
                val info = ProHelper.verify(code)
                if (info.valid) {
                    prefs.proActivated = true
                    prefs.proForever = info.forever
                    prefs.proExpireAt = info.expireAt
                    prefs.proCode = code
                    Toast.makeText(context, R.string.pro_activate_ok, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSuccess?.invoke()
                } else if (info.expired) {
                    // 周卡已过期：码是真的，但权益已失效。不要把过期信息写回激活状态，
                    // 并引导去获取新码；面板保持打开，用户可直接换码重试。
                    Toast.makeText(context, R.string.pro_activate_expired, Toast.LENGTH_LONG).show()
                    showBuy(context)
                } else {
                    Toast.makeText(context, R.string.pro_activate_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    /** 已激活状态：展示权益与到期信息，并提供「更换激活码」入口（解决找不到输入入口）。 */
    fun showStatus(context: Context, prefs: Prefs, onSuccess: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pro_card_title)
            .setMessage(
                context.getString(R.string.pro_sheet_status, ProHelper.statusText(prefs)) +
                    "\n\n" + context.getString(R.string.pro_sheet_benefits)
            )
            .setNeutralButton(R.string.pro_change_code) { _, _ -> showInput(context, prefs, onSuccess) }
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    /**
     * v1.7.6：无条件弹出「输入激活码」面板，供已激活用户换码/续期。
     * show() 的激活壳与输入逻辑抽到此处，避免与 showStatus 的分支互相耦合。
     */
    private fun showInput(context: Context, prefs: Prefs, onSuccess: (() -> Unit)?) {
        show(context, prefs, "", onSuccess, forceInput = true)
    }

    /**
     * v1.6.2 获取激活码引导页：把「付款 -> 拿码」补成可执行的闭环。
     *
     * 收款码做成动态资源检测：把图片放到 res/drawable/pay_qrcode.png 即自动展示，
     * 无需改代码；资源不存在时也能正常编译运行，改为显示文字引导 + 联系入口。
     */
    fun showBuy(context: Context) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = ThemeHelper.resolveColor(context, androidx.appcompat.R.attr.colorPrimary, R.color.primary)
        val textMain = ThemeHelper.resolveColor(context, android.R.attr.textColorPrimary, R.color.text_primary)
        val textSub = ThemeHelper.resolveColor(context, android.R.attr.textColorSecondary, R.color.text_secondary)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        root.addView(TextView(context).apply {
            text = context.getString(R.string.pro_buy_price)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.pro_buy_steps)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textMain)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(12), 0, dp(4))
        })

        val qrId = context.resources.getIdentifier("pay_qrcode", "drawable", context.packageName)
        if (qrId != 0) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.pro_buy_qr_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(textSub)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, dp(2))
            })
            val qr = ImageView(context).apply {
                setImageResource(qrId)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = context.getString(R.string.pro_buy_qr_hint)
                setOnClickListener { showQrZoom(context, qrId) }
            }
            root.addView(
                qr,
                LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(6)
                }
            )
        } else {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.pro_buy_qr_missing)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(textSub)
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        val scroll = ScrollView(context).apply { addView(root) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pro_buy_title)
            .setView(scroll)
            .setNeutralButton(R.string.pro_buy_contact) { _, _ -> openContact(context) }
            .setPositiveButton(R.string.pro_buy_back, null)
            .show()
    }

    /** 点按收款码放大，方便截图扫码（不请求存储权限、不写文件）。 */
    private fun showQrZoom(context: Context, qrId: Int) {
        val dm = context.resources.displayMetrics
        // v1.6.3：限制放大视图宽度，避免二维码被对话框裁切导致截不全、扫不出
        val maxW = minOf((dm.widthPixels * 0.82).toInt(), (420 * dm.density).toInt())
        val iv = ImageView(context).apply {
            setImageResource(qrId)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(iv, LinearLayout.LayoutParams(maxW, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pro_buy_qr_zoom)
            .setView(wrap)
            .setPositiveButton(R.string.action_ok, null)
            .create()
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 付款凭证 / 索取收款码的统一联系入口（复用设置页「意见反馈」的 Issues 通道）。 */
    private fun openContact(context: Context) {
        val url = "https://github.com/qiangsi/xiaodou-notify-android/issues/new"
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.pro_buy_open_fail, Toast.LENGTH_LONG).show()
        }
    }
}
