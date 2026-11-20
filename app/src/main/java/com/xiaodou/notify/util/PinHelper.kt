package com.xiaodou.notify.util

import java.security.SecureRandom
import java.security.MessageDigest

/**
 * 应用锁 PIN 工具：只保存「盐 + SHA-256(盐+ PIN)」，不保存明文。
 * 用于让用户自设密码，替代/补充系统生物识别验证。
 */
object PinHelper {

    fun isValidFormat(pin: String): Boolean =
        pin.length in 4..6 && pin.all { it.isDigit() }

    fun newSalt(): String {
        val buf = ByteArray(16)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hasPin(prefs: Prefs): Boolean =
        prefs.appLockPinHash.isNotBlank() && prefs.appLockPinSalt.isNotBlank()

    fun setPin(prefs: Prefs, pin: String) {
        if (prefs.appLockPinSalt.isBlank()) prefs.appLockPinSalt = newSalt()
        prefs.appLockPinHash = hash(pin, prefs.appLockPinSalt)
    }

    fun verify(prefs: Prefs, pin: String): Boolean {
        if (!hasPin(prefs)) return false
        return hash(pin, prefs.appLockPinSalt) == prefs.appLockPinHash
    }
}
