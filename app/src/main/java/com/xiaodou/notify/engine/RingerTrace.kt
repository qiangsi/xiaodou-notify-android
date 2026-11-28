package com.xiaodou.notify.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.xiaodou.notify.XiaodouApp
import com.xiaodou.notify.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 铃声黑匣子（v1.7.15）：只监听、绝不写铃声。
 *
 * 【为什么这样做】
 * 系统每次改铃声模式（无论谁改的：用户按音量键、系统会睡/勿扰、别的 App、本 App 的迁移器）
 * 都会发 AudioManager.RINGER_MODE_CHANGED 广播，新模式放在 EXTRA_RINGER_MODE。
 * 广播本身不携带「谁改的」——所以改成抓「现场证据」：
 *   时间 / 旧模式→新模式 / 勿扰(zen_mode) / 前台 App / 铃声音量 / 是否本 App 自己刚动过。
 * 这样用户反馈「又被静音」时，诊断报告里能直接看到那一刻的现场，定位真凶。
 *
 * 【只监听不写入】
 * 本类没有任何 setRingerMode / setStreamVolume 调用，不影响产品核心：
 * 通知管理 = 把不重要的通知静默接管（cancelNotification），收藏/守护的不提醒也不打扰。
 */
object RingerTrace {

    private const val TAG = "RingerTrace"
    private const val SELF_WINDOW_MS = 5_000L // 5 秒内本 App 动过铃声就标记为「本App」

    private var installed = false
    private var lastMode: Int = -1
    private var receiver: BroadcastReceiver? = null

    /** 在 Application.onCreate 调用一次。只监听，不写任何设置。 */
    fun install(ctx: Context) {
        if (installed) return
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        lastMode = try { am?.ringerMode ?: -1 } catch (e: Exception) { -1 }

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
                val newMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                onModeChanged(app, newMode)
            }
        }
        try {
            val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
            // targetSdk 33+ 动态注册必须声明导出性；这里只收系统广播，用 NOT_EXPORTED
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, filter)
            }
            receiver = r
            installed = true
            prefs(app)?.appendRingerTrace("【黑匣子已启动】当前模式=${modeName(lastMode)} ${nowText()}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "install failed: ${e.javaClass.simpleName}")
        }
    }

    /** 收到铃声模式变化：抓现场，写一条证据。 */
    private fun onModeChanged(ctx: Context, newMode: Int) {
        val oldMode = lastMode
        lastMode = newMode
        // 去重：同一模式重复广播（勿扰/音量键常连发）只记首次
        if (oldMode == newMode && newMode >= 0) return

        val p = prefs(ctx) ?: return
        val ts = nowText()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringVol = try { am?.getStreamVolume(AudioManager.STREAM_RING) } catch (e: Exception) { -1 }
        val zen = zenMode(ctx)
        val fg = foregroundApp(ctx)
        val selfChanged = System.currentTimeMillis() - p.selfRingerChangeAt < SELF_WINDOW_MS

        val who = when {
            selfChanged -> "本App"
            zen > 0 -> "系统勿扰(DND=$zen)"
            fg == "android" || fg == "com.android.systemui" -> "系统UI"
            fg.isNotBlank() -> "前台App=$fg"
            else -> "未知(用户/其它App)"
        }
        val verdict = if (newMode == AudioManager.RINGER_MODE_SILENT ||
            newMode == AudioManager.RINGER_MODE_VIBRATE || (ringVol ?: 1) <= 0
        ) "★静音" else "非静音"

        val line = "$ts $verdict ${modeName(oldMode)}→${modeName(newMode)} | 铃声=$ringVol | 勿扰=$zen | 前台=$fg | 来源=$who"
        p.appendRingerTrace(line)
        android.util.Log.d(TAG, line)
    }

    /**
     * 给「本 App 自己写铃声」打标记。由 LegacyMuteMigrator 在恢复动作前调用，
     * 让黑匣子能诚实区分「本App恢复」与「外部静音」——这是自证清白的关键。
     */
    fun markSelfChange(ctx: Context) {
        prefs(ctx)?.selfRingerChangeAt = System.currentTimeMillis()
    }

    private fun prefs(ctx: Context): Prefs? = (ctx as? XiaodouApp)?.prefs

    private fun nowText(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "静音"
        AudioManager.RINGER_MODE_VIBRATE -> "振动"
        AudioManager.RINGER_MODE_NORMAL -> "响铃"
        else -> "未知($mode)"
    }

    /** 勿扰模式：0=关闭 1=优先 2=完全静音 3=仅闹钟（各 ROM 语义略有差异） */
    private fun zenMode(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0)
    } catch (e: Exception) { -1 }

    /**
     * 变化瞬间的前台 App（尽力而为）。getRunningAppProcesses 在部分 ROM 上只返回自身进程，
     * 拿不到就标「未知」，不猜测、不误报。
     */
    private fun foregroundApp(ctx: Context): String = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val procs = am?.runningAppProcesses
        val fg = procs?.firstOrNull { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
        fg?.processName?.removeSuffix(":remote") ?: ""
    } catch (e: Exception) { "" }
}
