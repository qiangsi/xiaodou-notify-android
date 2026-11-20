package com.xiaodou.notify.util

import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaodou.notify.R

/**
 * FR-E5 应用锁验证编排（v1.4.0）。
 *
 * 职责：根据用户在设置页选择的解锁方式（PIN / 生物识别 / 两者皆可）分发到对应验证流程，
 * 并提供「忘记密码 → 安全问题验证 → 重置 PIN」的恢复闭环。
 * 不负责「何时需要验证」（由 [AppLockPolicy] 决定），也不负责生命周期（由 AppLockGuard 负责）。
 */
object AppLockHelper {

    const val METHOD_PIN = 0
    const val METHOD_BIOMETRIC = 1
    const val METHOD_BOTH = 2

    fun isBiometricCapable(context: Context): Boolean = try {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }

    /** 兼容旧调用：是否需要验证，实际判定请以 AppLockPolicy 为准。 */
    fun shouldPrompt(prefs: Prefs): Boolean = AppLockPolicy.needsVerification(prefs)

    /** 统一验证入口。按用户选择的方式分发，任何方式缺失时安全降级，绝不误放行。 */
    fun prompt(
        activity: FragmentActivity,
        prefs: Prefs,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        val hasPin = PinHelper.hasPin(prefs)
        when (prefs.appLockMethod) {
            METHOD_PIN -> {
                if (hasPin) promptPin(activity, prefs, onSuccess, onFail)
                else if (isBiometricCapable(activity)) promptBiometric(activity, prefs, onSuccess, onFail)
                else onFail()
            }
            METHOD_BIOMETRIC -> {
                if (isBiometricCapable(activity)) promptBiometric(activity, prefs, onSuccess, onFail)
                else if (hasPin) promptPin(activity, prefs, onSuccess, onFail)
                else onFail()
            }
            else -> { // METHOD_BOTH
                when {
                    hasPin -> promptPin(activity, prefs, onSuccess, onFail)
                    isBiometricCapable(activity) -> promptBiometric(activity, prefs, onSuccess, onFail)
                    else -> onFail()
                }
            }
        }
    }

    /** PIN 验证对话框：输错可重试，含「忘记密码」恢复入口。 */
    private fun promptPin(
        activity: FragmentActivity,
        prefs: Prefs,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        val input = pinField(activity, R.string.app_lock_pin_verify_title)
        var dialogRef: android.app.Dialog? = null
        // 弹窗是否已给出结论（验证成功 / 转入其他验证流程）。
        // false 时任何途径的关闭都算「验证未完成」，必须走 onFail 重新上锁。
        var settled = false
        // 用一个垂直容器放输入框 + 两个次级入口，避免占用对话框三个按钮位
        val extras = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val canBiometric = isBiometricCapable(activity)
        if (canBiometric) {
            val link = TextView(activity).apply {
                text = activity.getString(R.string.app_lock_use_biometric)
                setTextColor(activity.getColor(R.color.text_link))
                textSize = 13f
                setPadding(0, 12, 0, 0)
                setOnClickListener {
                    // 由调用方在对话框外重新发起生物识别；此处先关闭当前对话框
                    settled = true
                    dialogRef?.dismiss()
                    promptBiometric(activity, prefs, onSuccess, onFail)
                }
            }
            extras.addView(link)
        }
        if (LockRecovery.hasRecovery(prefs)) {
            val link = TextView(activity).apply {
                text = activity.getString(R.string.app_lock_forgot_pin)
                setTextColor(activity.getColor(R.color.text_link))
                textSize = 13f
                setPadding(0, 12, 0, 0)
                setOnClickListener {
                    settled = true
                    dialogRef?.dismiss()
                    showRecovery(activity, prefs, onSuccess, onFail)
                }
            }
            extras.addView(link)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            if (extras.childCount > 0) addView(extras)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.app_lock_pin_verify_title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialogRef = dialog
        // 任何途径关闭弹窗（取消按钮、返回键、系统销毁、Activity 重建）都算验证未完成，
        // 统一走 onFail 重新上锁，杜绝用户停留在已解锁的界面。
        dialog.setOnDismissListener { if (!settled) onFail() }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    input.text.toString().trim().isEmpty() ->
                        input.error = activity.getString(R.string.app_lock_pin_empty)
                    PinHelper.verify(prefs, input.text.toString().trim()) -> {
                        settled = true
                        dialog.dismiss()
                        onSuccess()
                    }
                    else -> {
                        input.error = activity.getString(R.string.app_lock_pin_wrong)
                        input.text?.clear()
                    }
                }
            }
        }
        dialog.show()
    }

    /** 备用恢复：先验证安全问题答案（或生物识别），通过后重置 PIN。 */
    private fun showRecovery(
        activity: FragmentActivity,
        prefs: Prefs,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        if (LockRecovery.hasRecovery(prefs)) {
            showSecurityQuestion(activity, prefs, onSuccess, onFail)
        } else if (isBiometricCapable(activity)) {
            // 未设置安全问题时，允许用系统生物识别作为恢复凭证
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.recovery_title)
                .setMessage(R.string.recovery_no_question_biometric)
                .setPositiveButton(R.string.recovery_use_biometric) { _, _ ->
                    promptBiometric(activity, prefs,
                        onSuccess = { showSetPinThen(activity, prefs, onSuccess, onFail) },
                        onFail = onFail)
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> onFail() }
                .setCancelable(false)
                .show()
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.recovery_title)
                .setMessage(R.string.recovery_impossible)
                .setPositiveButton(R.string.action_ok, null)
                .setCancelable(true)
                .show()
        }
    }

    private fun showSecurityQuestion(
        activity: FragmentActivity,
        prefs: Prefs,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        val idx = prefs.recoveryQuestionIndex
        val answer = pinField(activity, R.string.recovery_answer_hint, numeric = false)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.recovery_title)
            .setMessage(activity.getString(R.string.recovery_question_prompt, activity.getString(LockRecovery.QUESTIONS[idx.coerceIn(LockRecovery.QUESTIONS.indices)])))
            .setView(answer)
            .setCancelable(false)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel) { _, _ -> onFail() }
            .create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = answer.text.toString()
                        if (text.trim().isEmpty()) {
                            answer.error = activity.getString(R.string.recovery_answer_empty)
                        } else if (LockRecovery.verify(prefs, text)) {
                            dismiss()
                            showSetPinThen(activity, prefs, onSuccess, onFail)
                        } else {
                            answer.error = activity.getString(R.string.recovery_answer_wrong)
                            answer.text?.clear()
                        }
                    }
                }
                show()
            }
    }

    /** 恢复验证通过后，让用户设置新 PIN，成功后视为本次验证完成。 */
    private fun showSetPinThen(
        activity: FragmentActivity,
        prefs: Prefs,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        val first = pinField(activity, R.string.app_lock_pin_set_hint)
        val second = pinField(activity, R.string.app_lock_pin_confirm_hint)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(first)
            addView(second)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.app_lock_pin_set_title)
            .setMessage(R.string.recovery_reset_pin_hint)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel) { _, _ -> onFail() }
            .create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        when {
                            !PinHelper.isValidFormat(first.text.toString()) ->
                                first.error = activity.getString(R.string.app_lock_pin_invalid)
                            first.text.toString() != second.text.toString() ->
                                second.error = activity.getString(R.string.app_lock_pin_mismatch)
                            else -> {
                                PinHelper.setPin(prefs, first.text.toString())
                                dismiss()
                                Toast.makeText(activity, R.string.app_lock_pin_set_done, Toast.LENGTH_SHORT).show()
                                onSuccess()
                            }
                        }
                    }
                }
                show()
            }
    }

    private fun promptBiometric(
        activity: FragmentActivity,
        prefs: Prefs,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
            override fun onAuthenticationFailed() { /* 单次失败不关闭，继续重试 */ }
        }
        try {
            BiometricPrompt(activity, executor, callback).authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.app_lock_title))
                    .setSubtitle(activity.getString(R.string.app_lock_subtitle))
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
            )
        } catch (e: Exception) {
            onFail()
        }
    }

    private fun pinField(activity: Context, hintRes: Int, numeric: Boolean = true): EditText =
        EditText(activity).apply {
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            if (numeric) transformationMethod = PasswordTransformationMethod.getInstance()
            hint = activity.getString(hintRes)
            setSingleLine(true)
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
}
